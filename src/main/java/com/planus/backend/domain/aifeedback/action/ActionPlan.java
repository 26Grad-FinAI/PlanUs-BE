package com.planus.backend.domain.aifeedback.action;

/**
 * [6] 절약 액션 산출 결과. 카테고리별 감축 제안.
 *
 * @param categoryId       대상 카테고리 ID
 * @param reductionWon     제안 감축액 (원)
 * @param dominantFactor   초과 원인 ("FREQUENCY" / "UNIT_PRICE" / "BOTH" / null).
 *                         [7] LLM 렌더러가 액션 문구 유형을 결정하는 근거.
 * @param feasibilityScore 실행 가능성 점수 (높을수록 사용자가 받아들일 가능성 높음)
 */
public record ActionPlan(int categoryId, long reductionWon, String dominantFactor, double feasibilityScore) {}
