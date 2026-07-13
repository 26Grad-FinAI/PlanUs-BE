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
 *   <li>SATISFIED 비중 70% 이상 → 제외 (대안이 없으면 유지)
 * </ul>
 *
 * <p>점수 기반 우선순위 (feasibilityScore):
 * <ul>
 *   <li>REGRET 비중 → 가산 (사용자가 줄이고 싶어하는 카테고리)
 *   <li>SATISFIED 비중 → 감산
 *   <li>ALREADY_KNOWN 거부 → 감산
 *   <li>TOO_REPETITIVE 거부 → 대폭 감산
 *   <li>semi-essential → 감산
 * </ul>
 *
 * <p>절감액 = min(카테고리 초과분, 저축 갭 잔여분, 남은 기간 감축가능액)
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
    private static final double SATISFIED_EXCLUDE_RATIO = 0.70;
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
     * @param categoryMtdSpend       카테고리별 이번 달 누적 지출 (categoryId → 원)
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
            Map<Integer, Long> categoryMtdSpend,
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

        Map<Integer, Map<String, Long>> emotionByCategory = buildEmotionMap(thisMonthExpenses);
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

        // SATISFIED 비중 70%+ 제외 (대안이 없으면 유지)
        List<ScoredCategory> filtered =
                scored.stream().filter(sc -> !sc.satisfiedExcluded).toList();
        if (filtered.isEmpty()) {
            filtered = scored;
        }

        // 2단계: feasibilityScore 내림차순으로 갭 배분
        List<ScoredCategory> sortedByScore = filtered.stream()
                .sorted(Comparator.comparingDouble(ScoredCategory::score).reversed())
                .toList();

        List<ActionPlan> plans = new ArrayList<>();
        long remainingGap = savingsGap;

        for (ScoredCategory sc : sortedByScore) {
            if (remainingGap <= 0) break;

            // 남은 기간 감축가능액 = 일평균 × 잔여일 × 감축비율
            long mtdSpent = categoryMtdSpend.getOrDefault(sc.categoryId, 0L);
            double dailyAvg = mtdSpent / (double) elapsedDays;
            long remainingExpected = (long) (dailyAvg * remainingDays);
            long maxReducible = (long) (remainingExpected * p.getReductionRatio());

            long reductionWon = Math.min(sc.overspendWon, Math.min(remainingGap, maxReducible));

            if (reductionWon <= 0) continue;

            plans.add(new ActionPlan(sc.categoryId, reductionWon, sc.dominantFactor, sc.score));
            remainingGap -= reductionWon;
        }

        return plans;
    }

    private ScoredCategory toScoredCategory(
            int categoryId,
            long overspendWon,
            Set<Long> semiEssentialSet,
            Map<Integer, Map<String, Long>> emotionByCategory,
            Map<Integer, Map<RejectionReason, Long>> rejectionByCategory,
            Map<Integer, CauseSignals> causeSignalsByCategory) {

        double score = BASE_SCORE;
        boolean satisfiedExcluded = false;

        if (semiEssentialSet.contains((long) categoryId)) {
            score += SEMI_ESSENTIAL_PENALTY;
        }

        // 감정 기반 가감
        Map<String, Long> emotions = emotionByCategory.getOrDefault(categoryId, Map.of());
        long totalEmotionCount =
                emotions.values().stream().mapToLong(Long::longValue).sum();
        if (totalEmotionCount > 0) {
            double regretRatio = emotions.getOrDefault("REGRET", 0L) / (double) totalEmotionCount;
            double satisfiedRatio = emotions.getOrDefault("SATISFIED", 0L) / (double) totalEmotionCount;
            score += regretRatio * REGRET_BONUS;
            score += satisfiedRatio * SATISFIED_PENALTY;
            if (satisfiedRatio >= SATISFIED_EXCLUDE_RATIO) {
                satisfiedExcluded = true;
            }
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

        return new ScoredCategory(categoryId, overspendWon, score, satisfiedExcluded, dominantFactor);
    }

    /** 이번 달 변동 지출의 카테고리별 감정태그 집계. */
    private Map<Integer, Map<String, Long>> buildEmotionMap(List<Expense> expenses) {
        return expenses.stream()
                .filter(Expense::isVariable)
                .filter(e -> e.getEmotion() != null && !e.getEmotion().isBlank())
                .collect(Collectors.groupingBy(
                        Expense::getCategoryId, Collectors.groupingBy(Expense::getEmotion, Collectors.counting())));
    }

    /** 거부 이력의 카테고리별 사유 집계. */
    private Map<Integer, Map<RejectionReason, Long>> buildRejectionMap(List<UserReaction> rejections) {
        return rejections.stream()
                .filter(r -> r.getRejectionReason() != null && r.getCategoryId() != null)
                .collect(Collectors.groupingBy(
                        UserReaction::getCategoryId,
                        Collectors.groupingBy(UserReaction::getRejectionReason, Collectors.counting())));
    }

    private record ScoredCategory(
            int categoryId, long overspendWon, double score, boolean satisfiedExcluded, String dominantFactor) {}
}
