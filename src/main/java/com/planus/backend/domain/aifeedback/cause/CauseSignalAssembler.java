package com.planus.backend.domain.aifeedback.cause;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.entity.UserProfile;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * [5] 원인 신호 조립기.
 *
 * <p>초과 카테고리 1개에 대해 코드 계산 가능한 원인 신호를 조립한다.
 * 오케스트레이터(Issue 3)에서 카테고리별 반복 호출한다.
 *
 * <p>계층 1 — 파생 통계 4종 (코드 계산):
 * <ul>
 *   <li>본인 과거 대비 배율 (selfRatio)
 *   <li>빈도 vs 단가 분해 (dominantFactor)
 *   <li>감정태그 집계 (emotionCounts)
 *   <li>프로필 패턴 매칭 (repeatStreak)
 * </ul>
 *
 * <p>계층 2 — 메모 원문 (이번 주 + 지난주, 최신순 LIMIT 10)
 *
 * <p>이 클래스는 수치·신호만 조립한다. 문장 생성은 [7] LLM 렌더러의 책임.
 */
@Component
public class CauseSignalAssembler {

    private static final int MEMO_LIMIT = 10;
    private static final int MIN_ACTIVE_WEEKS = 3;
    private static final int BASELINE_WEEKS = 7;
    private static final long MIN_BASELINE_AVG = 10_000L;
    private static final double SELF_RATIO_THRESHOLD = 1.5;
    private static final double DOMINANT_CONTRIBUTION = 0.65;

    private static final Logger log = LoggerFactory.getLogger(CauseSignalAssembler.class);

    private final ObjectMapper objectMapper;

    public CauseSignalAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 하나의 초과 카테고리에 대해 원인 신호를 조립한다.
     *
     * @param categoryId  대상 카테고리 ID
     * @param allExpenses 해당 사용자의 전체 지출 내역 (카테고리 무관)
     * @param weekStart   이번 주 시작일 (월요일)
     * @param weekEnd     이번 주 종료일 (일요일)
     * @param profile     사용자 프로필 (repeat_patterns 참조)
     * @return 조립된 원인 신호
     */
    public CauseSignals assemble(
            int categoryId, List<Expense> allExpenses, LocalDate weekStart, LocalDate weekEnd, UserProfile profile) {

        List<Expense> categoryExpenses = allExpenses.stream()
                .filter(e -> e.getCategoryId() == categoryId)
                .toList();

        Double selfRatio = computeSelfRatio(categoryExpenses, weekStart, weekEnd);
        double[] freqPrice = decomposeFreqPrice(categoryExpenses, weekStart, weekEnd);
        String dominantFactor = determineDominant(freqPrice[0], freqPrice[1]);

        // 감정태그: 변동 지출만 집계 (고정·예정 지출의 감정태그는 행동 개선 대상이 아님)
        Map<String, Long> emotionCounts = countEmotions(categoryExpenses, weekStart, weekEnd);
        Map<String, Long> emotionAmounts = sumEmotionAmounts(categoryExpenses, weekStart, weekEnd);

        Integer repeatStreak = extractRepeatStreak(categoryId, profile);

        // 메모: 이번 주 + 지난주, 모든 지출 유형 포함
        // (고정 지출에도 메모를 다는 경우가 있고, LLM 맥락에 유용)
        LocalDate memoStart = weekStart.minusWeeks(1);
        List<MemoEvidence> memos = extractMemos(categoryExpenses, memoStart, weekEnd);

        return new CauseSignals(
                selfRatio,
                dominantFactor,
                freqPrice[0],
                freqPrice[1],
                emotionCounts,
                emotionAmounts,
                repeatStreak,
                memos,
                !memos.isEmpty());
    }

    /**
     * 본인 과거 대비 배율. 이번 주 합계 / 과거 7주 주당 평균.
     *
     * <p>분모는 항상 {@value BASELINE_WEEKS}(관측 기간). {@code activeWeeks}가 아님.
     * 평소 3주에 한 번 쓰던 사람이 이번 주에 몰아 쓰면 빈도 증가 신호를 잡아야 하기 때문.
     *
     * <p>가드:
     * <ul>
     *   <li>지출 있는 주가 {@value MIN_ACTIVE_WEEKS}주 미만 → null (너무 드문 카테고리)
     *   <li>주당 평균이 {@value MIN_BASELINE_AVG}원 미만 → null (소액 카테고리의 허위 배율 방지)
     * </ul>
     *
     * <p>변동 지출만 대상.
     */
    private Double computeSelfRatio(List<Expense> categoryExpenses, LocalDate weekStart, LocalDate weekEnd) {
        List<Expense> variable =
                categoryExpenses.stream().filter(Expense::isVariable).toList();

        double thisWeekSum = variable.stream()
                .filter(e -> !e.getDate().isBefore(weekStart) && !e.getDate().isAfter(weekEnd))
                .mapToLong(Expense::getAmount)
                .sum();

        LocalDate priorStart = weekStart.minusWeeks(BASELINE_WEEKS);
        List<Expense> priorExpenses = variable.stream()
                .filter(e -> !e.getDate().isBefore(priorStart) && e.getDate().isBefore(weekStart))
                .toList();

        // 지출이 있는 주 수 — 가드용 (분모 아님)
        long activeWeeks = priorExpenses.stream()
                .map(e -> weekNumber(e.getDate(), priorStart))
                .distinct()
                .count();

        if (activeWeeks < MIN_ACTIVE_WEEKS) return null;

        double priorWeeklyAvg =
                priorExpenses.stream().mapToLong(Expense::getAmount).sum() / (double) BASELINE_WEEKS;

        if (priorWeeklyAvg < MIN_BASELINE_AVG) return null;

        double ratio = thisWeekSum / priorWeeklyAvg;
        return ratio >= SELF_RATIO_THRESHOLD ? ratio : null;
    }

    /**
     * 빈도 vs 단가 로그 분해. 변동 지출만 대상.
     *
     * <p>이번 주 총액 = 빈도 × 평균단가. 과거 대비 변화를 로그 분해하여
     * 빈도 기여율과 단가 기여율을 반환한다.
     *
     * <p>증가 방향만 기여도로 반영한다. 감소 요인은 상쇄 요인이지 원인이 아니기 때문.
     * (예: 빈도 감소 + 단가 대폭 증가 → 단가가 100% 원인)
     *
     * @return [freqContribution, priceContribution] (0~1 범위). 산출 불가 시 [0, 0].
     */
    private double[] decomposeFreqPrice(List<Expense> categoryExpenses, LocalDate weekStart, LocalDate weekEnd) {
        List<Expense> variable =
                categoryExpenses.stream().filter(Expense::isVariable).toList();

        LocalDate priorStart = weekStart.minusWeeks(BASELINE_WEEKS);

        List<Expense> thisWeek = variable.stream()
                .filter(e -> !e.getDate().isBefore(weekStart) && !e.getDate().isAfter(weekEnd))
                .toList();
        List<Expense> prior = variable.stream()
                .filter(e -> !e.getDate().isBefore(priorStart) && e.getDate().isBefore(weekStart))
                .toList();

        if (thisWeek.isEmpty() || prior.isEmpty()) return new double[] {0, 0};

        double curFreq = thisWeek.size();
        double curPrice =
                thisWeek.stream().mapToLong(Expense::getAmount).average().orElse(0);
        double priorFreq = prior.size() / (double) BASELINE_WEEKS;
        double priorPrice =
                prior.stream().mapToLong(Expense::getAmount).average().orElse(0);

        if (priorFreq <= 0 || priorPrice <= 0 || curFreq <= 0 || curPrice <= 0) {
            return new double[] {0, 0};
        }

        // 증가 방향만 기여도로 반영 — 감소는 상쇄 요인이지 원인이 아님
        double logFreq = Math.max(Math.log(curFreq / priorFreq), 0);
        double logPrice = Math.max(Math.log(curPrice / priorPrice), 0);
        double total = logFreq + logPrice;

        if (total <= 0) return new double[] {0, 0}; // 둘 다 감소 → 분해 무의미

        return new double[] {logFreq / total, logPrice / total};
    }

    /**
     * 감정태그별 건수 집계. 이번 주 변동 지출만 대상.
     *
     * <p>변동 지출만 집계하는 이유: 고정·예정 지출의 감정태그는
     * 사용자가 통제 가능한 행동 개선 대상이 아니므로 원인 분석에 무의미.
     */
    private Map<String, Long> countEmotions(List<Expense> categoryExpenses, LocalDate weekStart, LocalDate weekEnd) {
        return categoryExpenses.stream()
                .filter(Expense::isVariable)
                .filter(e -> !e.getDate().isBefore(weekStart) && !e.getDate().isAfter(weekEnd))
                .filter(e -> e.getEmotion() != null && !e.getEmotion().isBlank())
                .collect(Collectors.groupingBy(Expense::getEmotion, Collectors.counting()));
    }

    /** 감정태그별 금액 합계. 이번 주 변동 지출만 대상. */
    private Map<String, Long> sumEmotionAmounts(List<Expense> categoryExpenses, LocalDate weekStart, LocalDate weekEnd) {
        return categoryExpenses.stream()
                .filter(Expense::isVariable)
                .filter(e -> !e.getDate().isBefore(weekStart) && !e.getDate().isAfter(weekEnd))
                .filter(e -> e.getEmotion() != null && !e.getEmotion().isBlank())
                .collect(Collectors.groupingBy(Expense::getEmotion,
                        Collectors.summingLong(Expense::getAmount)));
    }

    /**
     * 프로필의 repeat_patterns에서 해당 카테고리의 streak 값을 추출한다.
     *
     * <p>repeat_patterns JSON 형식 예: {@code {"1":{"streak":4,"since":"2026-06"}}}
     *
     * @return streak 값. 패턴 미존재 또는 파싱 실패 시 null.
     */
    private Integer extractRepeatStreak(int categoryId, UserProfile profile) {
        if (profile == null || profile.getRepeatPatterns() == null) return null;
        try {
            Map<String, Map<String, Object>> patterns = objectMapper.readValue(
                    profile.getRepeatPatterns(), new TypeReference<Map<String, Map<String, Object>>>() {});
            Map<String, Object> entry = patterns.get(String.valueOf(categoryId));
            if (entry == null) return null;
            Object streak = entry.get("streak");
            if (streak instanceof Number n) return n.intValue();
            return null;
        } catch (JsonProcessingException e) {
            log.warn("repeat_patterns 파싱 실패: userId={}", profile.getUserId(), e);
            return null;
        }
    }

    /**
     * 메모 원문 추출. 이번 주 + 지난주 범위, 최신순 LIMIT {@value MEMO_LIMIT}.
     *
     * <p>모든 지출 유형(고정 포함)에서 추출한다. 고정 지출에도 사용자가 메모를 남기는 경우가
     * 있고, LLM이 맥락을 파악하는 데 유용하기 때문이다.
     */
    private List<MemoEvidence> extractMemos(List<Expense> categoryExpenses, LocalDate memoStart, LocalDate weekEnd) {
        return categoryExpenses.stream()
                .filter(e -> !e.getDate().isBefore(memoStart) && !e.getDate().isAfter(weekEnd))
                .filter(e -> e.getMemo() != null && !e.getMemo().isBlank())
                .sorted(Comparator.comparing(Expense::getDate).reversed())
                .limit(MEMO_LIMIT)
                .map(e -> new MemoEvidence(e.getDate(), e.getAmount(), e.getMemo()))
                .toList();
    }

    private String determineDominant(double freqContribution, double priceContribution) {
        if (freqContribution <= 0 && priceContribution <= 0) return null;
        if (freqContribution >= DOMINANT_CONTRIBUTION) return "FREQUENCY";
        if (priceContribution >= DOMINANT_CONTRIBUTION) return "UNIT_PRICE";
        return "BOTH";
    }

    /** 기준일로부터 몇 번째 주인지 (0-indexed). */
    private int weekNumber(LocalDate date, LocalDate origin) {
        return (int) ((date.toEpochDay() - origin.toEpochDay()) / 7);
    }
}
