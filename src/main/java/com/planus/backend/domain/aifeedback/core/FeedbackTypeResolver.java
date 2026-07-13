package com.planus.backend.domain.aifeedback.core;

import org.springframework.stereotype.Component;

/**
 * [4] 피드백 유형 결정. 항상 레코드가 생성되며, 세 유형 중 하나로 분류된다.
 *
 * <p>판정 우선순위:
 * <ol>
 *   <li>{@code LOW_DATA} — [1.5] 활동성 가드에서 기록 부족 판정
 *   <li>{@code ALERT} — 저축 초과 위험 / HIGH 이상치 / 카테고리 예산 초과
 *   <li>{@code POSITIVE} — 위 조건 모두 해당 없음
 * </ol>
 */
@Component
public class FeedbackTypeResolver {

    /**
     * 피드백 유형을 결정한다.
     *
     * @param isLowData             [1.5] 기록 부족 여부
     * @param savingsImpact         가용예산 − 예상총지출 (음수 = 초과 위험)
     * @param hasHighAnomaly        HIGH 이상치 존재 여부
     * @param hasOverBudgetCategory 예산 초과 카테고리 존재 여부
     * @return 피드백 유형
     */
    public FeedbackType resolve(
            boolean isLowData, long savingsImpact, boolean hasHighAnomaly, boolean hasOverBudgetCategory) {
        if (isLowData) {
            return FeedbackType.LOW_DATA;
        }
        if (savingsImpact < 0 || hasHighAnomaly || hasOverBudgetCategory) {
            return FeedbackType.ALERT;
        }
        return FeedbackType.POSITIVE;
    }
}
