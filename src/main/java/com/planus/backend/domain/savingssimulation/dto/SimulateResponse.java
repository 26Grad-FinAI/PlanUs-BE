package com.planus.backend.domain.savingssimulation.dto;

import java.util.List;

/**
 * 절약 시뮬레이션 결과 응답 DTO.
 *
 * @param displayType             화면 분기 유형
 * @param currentMonthlySavings   현재 월간 저축 가능액 (원)
 * @param projectedMonthlySavings 감축 후 월간 저축 가능액 (원)
 * @param reductionTotal          이번 달 남은 기간에 줄일 금액 합계 (원)
 * @param monthlySaving           같은 습관을 유지하면 월 절약액 합계 (원)
 * @param annualSaving            월 절약액 × 12 (원)
 * @param message                 사용자 안내 메시지
 * @param selectedActions         선택된 절약 제안 목록
 */
public record SimulateResponse(
        DisplayType displayType,
        long currentMonthlySavings,
        long projectedMonthlySavings,
        long reductionTotal,
        long monthlySaving,
        long annualSaving,
        String message,
        List<SuggestResponse.ActionCard> selectedActions) {

    public enum DisplayType {
        /** 저축 목표 있음 — 달성 단축 */
        GOAL,
        /** 현재 적자 — 균형 도달 */
        DEFICIT,
        /** 저축 목표 미달 — 달성 가능 */
        RECOVER,
        /** 여유 — 추가 절약 */
        SURPLUS
    }
}
