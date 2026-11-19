package com.planus.backend.domain.aifeedback.action;

import com.planus.backend.domain.aifeedback.cause.CauseSignals;
import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.entity.RejectionReason;
import com.planus.backend.domain.aifeedback.entity.UserReaction;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * [6] 절약 액션 산출기.
 *
 * <p>초과 카테고리별 감축 제안을 생성한다. 저축 갭(음수 savingsImpact)을 메우기 위해
 * 어떤 카테고리에서 얼마를 줄일 수 있는지 산출한다.
 *
 * <p>필터링:
 * <ul>
 *   <li>essential 카테고리 → 제외
 *   <li>sensitiveAreas 카테고리 (DONT_WANT_REDUCE 2회+) → 제외
 * </ul>
 *
 * <p>감정 태그로는 제외하지 않는다 (#141). 만족한 지출이라도 예산을 넘겼으면 절약 대상이고, 줄이고 싶지 않다는 판단은 사용자의
 * 거절 반응({@code sensitiveAreas})으로만 받는다. 건수 기준 70% 일괄 제외는 가장 큰 초과 카테고리를 빼 원인과 처방이 엇갈리게 했다
 * (#140 J035; 측정: 초과가 있는 주 중 최대 초과 카테고리가 이 규칙으로 빠진 주 3/28).
 *
 * <p>점수 기반 우선순위 (feasibilityScore) — 감정 비중은 건수가 아니라 금액 기준이다:
 * <ul>
 *   <li>REGRET 금액 비중 → 가산 (사용자가 줄이고 싶어하는 카테고리)
 *   <li>SATISFIED 금액 비중 → 감산
 *   <li>태그가 붙은 금액이 그 카테고리 변동 지출의 {@value #MIN_TAG_COVERAGE_PCT}% 미만이면 감정 점수를 쓰지 않는다 (태그는 선택 입력)
 *   <li>ALREADY_KNOWN 거부 → 감산
 *   <li>TOO_REPETITIVE 거부 → 대폭 감산
 *   <li>semi-essential → 감산
 * </ul>
 *
 * <p>절감액 = min(카테고리 초과분, 저축 갭 잔여분, 남은 기간 감축가능액)
 *
 * <p>절감액이 {@code min-action-won} 또는 저축 갭 × {@code min-action-gap-ratio} 미만이면 그 액션은 만들지 않는다 — 사용자에게 의미가 없고
 * 피드백 문장에 다 담을 수 없다 (#143).
 *
 * <p>월초(경과 3일 미만)에는 일평균 추정이 극단적으로 부정확하므로 액션을 생성하지 않는다.
 */
@Component
public class SavingsActionCalculator {

    private static final double BASE_SCORE = 1.0;
    private static final double REGRET_BONUS = 0.3;
    private static final double SATISFIED_PENALTY = -0.5;
    private static final double ALREADY_KNOWN_PENALTY = -0.2;
    private static final double TOO_REPETITIVE_PENALTY = -0.4;
    private static final double SEMI_ESSENTIAL_PENALTY = -0.15;
    /** 감정 점수를 쓰려면 태그가 붙은 금액이 카테고리 변동 지출의 이 비율(%) 이상이어야 한다. */
    private static final int MIN_TAG_COVERAGE_PCT = 50;
    /** 월초 가드: 경과일이 이 값 미만이면 액션을 생성하지 않는다. */
    private static final int MIN_ELAPSED_DAYS = 3;

    private final AiFeedbackProperties p;

    public SavingsActionCalculator(AiFeedbackProperties p) {
        this.p = p;
    }

    /**
     * 절약 액션을 산출한다.
     *
     * @param overspend              카테고리별 초과액 (양수만 포함, categoryId → 초과원)
     * @param savingsGap             저축 갭 (양수: 메워야 할 금액)
     * @param elapsedDays            월 경과일
     * @param remainingDays          월 잔여일
     * @param categoryRemainingExpected 카테고리별 남은 기간 예상 변동 지출 (categoryId → 원)
     * @param causeSignalsByCategory [5]에서 조립한 카테고리별 원인 신호 (dominantFactor 참조)
     * @param thisMonthExpenses      이번 달 지출 내역 (감정태그 참조)
     * @param rejections             거부 이력 (NOT_HELPFUL 반응만)
     * @param sensitiveAreas         user_profile.sensitive_areas 카테고리 ID 목록
     * @return feasibilityScore 내림차순 정렬된 액션 리스트
     */
    public List<ActionPlan> calculate(
            Map<Integer, Long> overspend,
            long savingsGap,
            int elapsedDays,
            int remainingDays,
            Map<Integer, Long> categoryRemainingExpected,
            Map<Integer, CauseSignals> causeSignalsByCategory,
            List<Expense> thisMonthExpenses,
            List<UserReaction> rejections,
            List<Long> sensitiveAreas) {

        if (savingsGap <= 0 || remainingDays <= 0 || elapsedDays < MIN_ELAPSED_DAYS) {
            return List.of();
        }

        Set<Long> essentialSet = new HashSet<>(p.getCategory().getEssential());
        Set<Long> semiEssentialSet = new HashSet<>(p.getCategory().getSemiEssential());
        Set<Long> sensitiveSet = new HashSet<>(sensitiveAreas);

        Map<Integer, EmotionAmounts> emotionByCategory = buildEmotionMap(thisMonthExpenses);
        Map<Integer, Map<RejectionReason, Long>> rejectionByCategory = buildRejectionMap(rejections);

        // 1단계: 필터 + 점수 계산
        List<ScoredCategory> scored = overspend.entrySet().stream()
                .filter(e -> !essentialSet.contains((long) e.getKey()))
                .filter(e -> !sensitiveSet.contains((long) e.getKey()))
                .map(e -> toScoredCategory(
                        e.getKey(),
                        e.getValue(),
                        semiEssentialSet,
                        emotionByCategory,
                        rejectionByCategory,
                        causeSignalsByCategory))
                .toList();

        // 2단계: feasibilityScore 내림차순으로 갭 배분
        List<ScoredCategory> sortedByScore = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredCategory::score).reversed())
                .toList();

        List<ActionPlan> plans = new ArrayList<>();
        long remainingGap = savingsGap;
        long minReduction = Math.max(p.getMinActionWon(), Math.round(savingsGap * p.getMinActionGapRatio()));

        for (ScoredCategory sc : sortedByScore) {
            if (remainingGap <= 0) break;

            // 남은 기간 감축가능액 = 남은 기간 예상 지출 × 감축비율
            long remainingExpected = categoryRemainingExpected.getOrDefault(sc.categoryId, 0L);
            long maxReducible = (long) (remainingExpected * p.getReductionRatio());

            long reductionWon = Math.min(sc.overspendWon, Math.min(remainingGap, maxReducible));

            // 사소한 액션은 건너뛰고 갭을 다음 카테고리에 남긴다
            if (reductionWon <= 0 || reductionWon < minReduction) continue;

            plans.add(new ActionPlan(sc.categoryId, reductionWon, sc.dominantFactor, sc.score));
            remainingGap -= reductionWon;
        }

        return plans;
    }

    private ScoredCategory toScoredCategory(
            int categoryId,
            long overspendWon,
            Set<Long> semiEssentialSet,
            Map<Integer, EmotionAmounts> emotionByCategory,
            Map<Integer, Map<RejectionReason, Long>> rejectionByCategory,
            Map<Integer, CauseSignals> causeSignalsByCategory) {

        double score = BASE_SCORE;

        if (semiEssentialSet.contains((long) categoryId)) {
            score += SEMI_ESSENTIAL_PENALTY;
        }

        // 감정 기반 가감 — 금액 비중. 태그가 충분히 붙은 카테고리에만 쓴다
        EmotionAmounts emotions = emotionByCategory.get(categoryId);
        if (emotions != null && emotions.hasEnoughTags()) {
            score += emotions.ratio("REGRET") * REGRET_BONUS;
            score += emotions.ratio("SATISFIED") * SATISFIED_PENALTY;
        }

        // 거부 이력 기반 가감
        Map<RejectionReason, Long> categoryRejections = rejectionByCategory.getOrDefault(categoryId, Map.of());
        if (categoryRejections.containsKey(RejectionReason.ALREADY_KNOWN)) {
            score += ALREADY_KNOWN_PENALTY;
        }
        if (categoryRejections.containsKey(RejectionReason.TOO_REPETITIVE)) {
            score += TOO_REPETITIVE_PENALTY;
        }

        // [5]에서 조립한 dominantFactor
        CauseSignals signals = causeSignalsByCategory.get(categoryId);
        String dominantFactor = (signals != null) ? signals.dominantFactor() : null;

        return new ScoredCategory(categoryId, overspendWon, score, dominantFactor);
    }

    /** 이번 달 변동 지출의 카테고리별 감정태그 금액 집계. */
    private Map<Integer, EmotionAmounts> buildEmotionMap(List<Expense> expenses) {
        Map<Integer, EmotionAmounts> out = new HashMap<>();
        for (Expense e : expenses) {
            if (!e.isVariable()) continue;
            out.computeIfAbsent(e.getCategoryId(), k -> new EmotionAmounts()).add(e.getEmotion(), e.getAmount());
        }
        return out;
    }

    /** 카테고리의 변동 지출 금액과 감정 태그별 금액. */
    private static final class EmotionAmounts {
        private long variableTotal;
        private long taggedTotal;
        private final Map<String, Long> byEmotion = new HashMap<>();

        void add(String emotion, long amount) {
            variableTotal += amount;
            if (emotion == null || emotion.isBlank()) return;
            taggedTotal += amount;
            byEmotion.merge(emotion, amount, Long::sum);
        }

        /** 태그가 붙은 금액이 변동 지출의 {@value #MIN_TAG_COVERAGE_PCT}% 이상인지. */
        boolean hasEnoughTags() {
            return taggedTotal > 0 && taggedTotal * 100 >= variableTotal * MIN_TAG_COVERAGE_PCT;
        }

        /** 태그가 붙은 금액 중 해당 감정의 비중. */
        double ratio(String emotion) {
            return taggedTotal == 0 ? 0 : byEmotion.getOrDefault(emotion, 0L) / (double) taggedTotal;
        }
    }

    /** 거부 이력의 카테고리별 사유 집계. */
    private Map<Integer, Map<RejectionReason, Long>> buildRejectionMap(List<UserReaction> rejections) {
        return rejections.stream()
                .filter(r -> r.getRejectionReason() != null && r.getCategoryId() != null)
                .collect(Collectors.groupingBy(
                        UserReaction::getCategoryId,
                        Collectors.groupingBy(UserReaction::getRejectionReason, Collectors.counting())));
    }

    private record ScoredCategory(int categoryId, long overspendWon, double score, String dominantFactor) {}
}
