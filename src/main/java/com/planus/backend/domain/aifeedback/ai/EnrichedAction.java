package com.planus.backend.domain.aifeedback.ai;

/**
 * Spring AI Structured Output으로 매핑되는 행동 강화 액션.
 *
 * <p>기존 {@link com.planus.backend.domain.aifeedback.action.ActionPlan}의 금액 수준 제안을
 * 행동 수준(빈도·요일·감정 패턴)으로 강화한 결과.
 *
 * @param categoryId          대상 카테고리 ID
 * @param reductionWon        제안 감축액 (원)
 * @param dominantFactor      초과 원인 ("FREQUENCY" / "UNIT_PRICE" / "BOTH" / null)
 * @param feasibilityScore    실행 가능성 점수
 * @param actionType          행동 유형 ("REDUCE_FREQUENCY" / "REDUCE_UNIT_PRICE" / "LIMIT_IMPULSE")
 * @param currentBehavior     현재 행동 설명 (예: "주 4.2회 외식")
 * @param targetBehavior      목표 행동 설명 (예: "주 2회로 감소")
 * @param contextInsight      맥락 인사이트 (예: "금요일 저녁 외식이 전체의 60%")
 * @param behaviorFeasibility 행동 실현 가능성 (0.0~1.0)
 */
public record EnrichedAction(
        int categoryId,
        long reductionWon,
        String dominantFactor,
        double feasibilityScore,
        String actionType,
        String currentBehavior,
        String targetBehavior,
        String contextInsight,
        double behaviorFeasibility) {}
