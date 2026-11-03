package com.planus.backend.domain.aifeedback.ai;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI Tool Calling으로 호출되는 소비 패턴 분석 도구.
 *
 * <p>AI가 행동 수준 절약 제안을 생성할 때 필요한 사용자의 소비 패턴 데이터를 DB에서 조회한다.
 */
@Component
@RequiredArgsConstructor
public class SavingsAnalysisTools {

    private final ExpenseRepository expenseRepository;

    /** 빈도 통계 결과. */
    public record FrequencyStats(double weeklyAvgCount, long avgAmountPerTransaction) {}

    /** 감정별 통계. */
    public record EmotionStat(long count, long totalAmount, double ratio) {}

    @Tool(description = "카테고리의 주간 거래 빈도와 건당 평균 금액을 조회한다")
    public FrequencyStats getCategoryFrequency(
            @ToolParam(description = "사용자 ID") Long userId,
            @ToolParam(description = "카테고리 ID") int categoryId,
            @ToolParam(description = "조회 주 수") int weeks) {

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusWeeks(weeks);
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.atTime(LocalTime.MAX);

        List<Expense> expenses =
                expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(userId, categoryId, from, to);
        List<Expense> variable =
                expenses.stream().filter(e -> e.isExpense() && e.isVariable()).toList();

        if (variable.isEmpty()) {
            return new FrequencyStats(0, 0);
        }

        double weeklyAvg = (double) variable.size() / weeks;
        long avgAmount = variable.stream().mapToLong(Expense::getAmount).sum() / variable.size();
        return new FrequencyStats(weeklyAvg, avgAmount);
    }

    @Tool(description = "카테고리의 요일별 지출 분포를 조회한다")
    public Map<String, Long> getDayOfWeekDistribution(
            @ToolParam(description = "사용자 ID") Long userId, @ToolParam(description = "카테고리 ID") int categoryId) {

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusWeeks(8);
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.atTime(LocalTime.MAX);

        List<Expense> expenses =
                expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(userId, categoryId, from, to);

        Map<String, Long> distribution = new LinkedHashMap<>();
        for (DayOfWeek dow : DayOfWeek.values()) {
            distribution.put(dow.name(), 0L);
        }
        for (Expense e : expenses) {
            if (!e.isExpense() || !e.isVariable()) continue;
            String day = e.getDate().getDayOfWeek().name();
            distribution.merge(day, e.getAmount(), Long::sum);
        }
        return distribution;
    }

    @Tool(description = "카테고리의 감정 태그별 거래 비율과 금액을 조회한다")
    public Map<String, EmotionStat> getEmotionDistribution(
            @ToolParam(description = "사용자 ID") Long userId, @ToolParam(description = "카테고리 ID") int categoryId) {

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusWeeks(8);
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.atTime(LocalTime.MAX);

        List<Expense> expenses =
                expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(userId, categoryId, from, to);
        List<Expense> variable =
                expenses.stream().filter(e -> e.isExpense() && e.isVariable()).toList();

        if (variable.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (Expense e : variable) {
            String emotion = e.getEmotion();
            if (emotion == null || emotion.isBlank()) emotion = "UNTAGGED";
            counts.merge(emotion, 1L, Long::sum);
            amounts.merge(emotion, e.getAmount(), Long::sum);
        }

        long total = variable.size();
        Map<String, EmotionStat> result = new LinkedHashMap<>();
        for (String emotion : counts.keySet()) {
            result.put(
                    emotion,
                    new EmotionStat(counts.get(emotion), amounts.get(emotion), (double) counts.get(emotion) / total));
        }
        return result;
    }
}
