package com.planus.backend.domain.aifeedback.ai;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI Tool Calling으로 호출되는 소비 패턴 분석 도구.
 *
 * <p>AI가 행동 수준 절약 제안을 생성할 때 필요한 사용자의 소비 패턴 데이터를 DB에서 조회한다.
 *
 * <p>기간 (#127): 절약 제안은 이번 달을 위한 것이므로 모든 도구가 같은 기간을 쓴다.
 * <ul>
 *   <li>이번 달 — 이번 달 1일 ~ 기준일
 *   <li>평소 — 이번 달 직전 3개월 (빈도 도구만, 비교 기준)
 * </ul>
 * 기준일은 {@link ToolContext}의 {@value #AS_OF} (피드백 주의 마지막 날). 없으면 오늘.
 *
 * <p>이전에는 빈도는 LLM이 정한 주 수, 요일·감정은 8주로 기간이 달라, 한 카드에 "주 1.75회·건당 11,064원"(4주)과 "전체의 63.5%"(8주)가 섞여
 * 나왔다. 비율·합계도 도구가 계산해 돌려줘 LLM이 직접 나누지 않게 한다.
 */
@Component
@RequiredArgsConstructor
public class SavingsAnalysisTools {

    /** ToolContext 키: 기준일 ({@link LocalDate}). */
    public static final String AS_OF = "asOf";

    /** 평소 기준 개월 수. */
    static final int USUAL_MONTHS = 3;

    private final ExpenseRepository expenseRepository;

    /**
     * 한 기간의 거래 통계.
     *
     * @param from                    시작일
     * @param to                      종료일
     * @param days                    기간 일수
     * @param count                   거래 건수
     * @param weeklyAvgCount          주당 평균 건수 (건수 ÷ 일수 × 7)
     * @param totalAmount             합계 (원)
     * @param avgAmountPerTransaction 건당 평균 (원)
     * @param monthlyAvgAmount        월평균 합계 (원) — 평소 기간에서 의미, 이번 달은 합계와 같음
     */
    public record PeriodStats(
            LocalDate from,
            LocalDate to,
            long days,
            long count,
            double weeklyAvgCount,
            long totalAmount,
            long avgAmountPerTransaction,
            long monthlyAvgAmount) {}

    /** 빈도 통계: 이번 달과 평소(직전 3개월). */
    public record FrequencyStats(PeriodStats thisMonth, PeriodStats usual) {}

    /**
     * 요일 하나의 지출.
     *
     * @param amount 금액 (원)
     * @param share  이번 달 이 카테고리 지출 중 비중 (0~1)
     */
    public record DayShare(long amount, double share) {}

    /** 이번 달 요일별 지출 분포. */
    public record DayOfWeekStats(LocalDate from, LocalDate to, long totalAmount, Map<String, DayShare> byDay) {}

    /**
     * 감정 태그 하나의 통계.
     *
     * @param count       건수
     * @param totalAmount 금액 (원)
     * @param ratio       건수 비중 (0~1)
     * @param amountShare 금액 비중 (0~1)
     */
    public record EmotionStat(long count, long totalAmount, double ratio, double amountShare) {}

    /** 이번 달 감정 태그별 분포. */
    public record EmotionStats(
            LocalDate from, LocalDate to, long totalCount, long totalAmount, Map<String, EmotionStat> byEmotion) {}

    @Tool(description = "카테고리의 이번 달(1일~기준일)과 평소(직전 3개월) 거래 빈도·건당 평균·합계를 조회한다")
    public FrequencyStats getCategoryFrequency(
            @ToolParam(description = "카테고리 ID") int categoryId, ToolContext toolContext) {
        LocalDate asOf = asOf(toolContext);
        LocalDate monthStart = asOf.withDayOfMonth(1);
        LocalDate usualStart = monthStart.minusMonths(USUAL_MONTHS);
        List<Expense> all = variable(toolContext, categoryId, usualStart, asOf);

        List<Expense> thisMonth =
                all.stream().filter(e -> !e.getDate().isBefore(monthStart)).toList();
        List<Expense> usual =
                all.stream().filter(e -> e.getDate().isBefore(monthStart)).toList();
        return new FrequencyStats(
                stats(thisMonth, monthStart, asOf, 1), stats(usual, usualStart, monthStart.minusDays(1), USUAL_MONTHS));
    }

    @Tool(description = "카테고리의 이번 달(1일~기준일) 요일별 지출 금액과 비중을 조회한다")
    public DayOfWeekStats getDayOfWeekDistribution(
            @ToolParam(description = "카테고리 ID") int categoryId, ToolContext toolContext) {
        LocalDate asOf = asOf(toolContext);
        LocalDate monthStart = asOf.withDayOfMonth(1);
        List<Expense> expenses = variable(toolContext, categoryId, monthStart, asOf);

        Map<String, Long> amounts = new LinkedHashMap<>();
        for (DayOfWeek dow : DayOfWeek.values()) amounts.put(dow.name(), 0L);
        for (Expense e : expenses) amounts.merge(e.getDate().getDayOfWeek().name(), e.getAmount(), Long::sum);
        long total = amounts.values().stream().mapToLong(Long::longValue).sum();

        Map<String, DayShare> byDay = new LinkedHashMap<>();
        amounts.forEach((day, amount) -> byDay.put(day, new DayShare(amount, share(amount, total))));
        return new DayOfWeekStats(monthStart, asOf, total, byDay);
    }

    @Tool(description = "카테고리의 이번 달(1일~기준일) 감정 태그별 건수·금액과 비중을 조회한다")
    public EmotionStats getEmotionDistribution(
            @ToolParam(description = "카테고리 ID") int categoryId, ToolContext toolContext) {
        LocalDate asOf = asOf(toolContext);
        LocalDate monthStart = asOf.withDayOfMonth(1);
        List<Expense> expenses = variable(toolContext, categoryId, monthStart, asOf);

        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (Expense e : expenses) {
            String emotion = e.getEmotion();
            if (emotion == null || emotion.isBlank()) emotion = "UNTAGGED";
            counts.merge(emotion, 1L, Long::sum);
            amounts.merge(emotion, e.getAmount(), Long::sum);
        }
        long totalCount = expenses.size();
        long totalAmount = amounts.values().stream().mapToLong(Long::longValue).sum();

        Map<String, EmotionStat> byEmotion = new LinkedHashMap<>();
        for (String emotion : counts.keySet()) {
            long count = counts.get(emotion);
            long amount = amounts.get(emotion);
            byEmotion.put(
                    emotion, new EmotionStat(count, amount, share(count, totalCount), share(amount, totalAmount)));
        }
        return new EmotionStats(monthStart, asOf, totalCount, totalAmount, byEmotion);
    }

    // ── 내부 ──

    private static LocalDate asOf(ToolContext toolContext) {
        Object v = toolContext.getContext().get(AS_OF);
        return v instanceof LocalDate d ? d : LocalDate.now();
    }

    private List<Expense> variable(ToolContext toolContext, int categoryId, LocalDate from, LocalDate to) {
        Long userId = (Long) toolContext.getContext().get("userId");
        return expenseRepository
                .findByUserIdAndCategoryIdAndExpenseDateBetween(
                        userId, categoryId, from.atStartOfDay(), to.atTime(LocalTime.MAX))
                .stream()
                .filter(Expense::isVariable)
                .toList();
    }

    private static PeriodStats stats(List<Expense> expenses, LocalDate from, LocalDate to, int months) {
        long days = Math.max(1, ChronoUnit.DAYS.between(from, to) + 1);
        long count = expenses.size();
        long total = expenses.stream().mapToLong(Expense::getAmount).sum();
        return new PeriodStats(
                from,
                to,
                days,
                count,
                round2(count * 7.0 / days),
                total,
                count == 0 ? 0 : total / count,
                Math.round((double) total / months));
    }

    private static double share(long part, long whole) {
        return whole == 0 ? 0.0 : round3((double) part / whole);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
