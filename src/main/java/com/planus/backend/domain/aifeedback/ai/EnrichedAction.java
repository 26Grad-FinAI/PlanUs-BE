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
 * @param currentBehavior     현재 행동 한 줄 요약, 25자 이내 (예: "이번 달 쇼핑 3번, 한 번에 6만 원꼴")
 * @param targetBehavior      바로 해볼 행동, 25자 이내 (예: "사고 싶을 땐 하루 미루고 결정하기")
 * @param contextInsight      패턴 한 줄, 30자 이내 (예: "주말 충동구매가 대부분이에요")
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
