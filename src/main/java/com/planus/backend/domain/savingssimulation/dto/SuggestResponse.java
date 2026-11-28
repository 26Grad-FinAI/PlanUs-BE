package com.planus.backend.domain.savingssimulation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 절약 제안 목록 응답 DTO.
 *
 * @param feedbackId               주간 피드백 ID
 * @param applied                  해당 사용자·피드백의 예산 반영 여부
 * @param actions                  절약 제안 카드 목록
 * @param totalPossibleReduction   이번 달 남은 기간에 줄일 금액 합계 (원)
 * @param totalMonthlySaving       같은 습관을 유지하면 월 절약액 합계 (원)
 * @param totalAnnualSaving        월 절약액 합계 × 12 (원)
 * @param comparison               피드백 이후 변화 한 줄 (예: "11/9 피드백 이후 저축 목표까지 부족액 29만 원 → 33만 원"). 비교할 수 없으면 null
 */
public record SuggestResponse(
        @Schema(description = "주간 피드백 ID", example = "123") Long feedbackId,
        @Schema(description = "예산 반영 여부 (주간 피드백 전체 기준)", example = "false") boolean applied,
        List<ActionCard> actions,
        long totalPossibleReduction,
        long totalMonthlySaving,
        long totalAnnualSaving,
        String comparison) {

    /**
     * @param reductionWon     이번 달 남은 기간에 줄일 금액 — 예산 반영 금액
     * @param monthlySavingWon 같은 습관을 한 달 내내 유지하면 절약되는 금액
     * @param annualSavingWon  monthlySavingWon × 12
     * @param method           거래 데이터로 계산한 절약 방법 (예: "남은 20일간 3번 덜 쓰기 (예상 10번 → 7번)"). 계산할 수 없으면 null
     * @param feasibilityLevel 실현 가능성 등급 EASY / NORMAL / HARD — 필요 감축 비율·과거 달성·감정·거절 이력으로 계산
     * @param feasibilityReasons 등급의 근거 문장
     * @param source           FEEDBACK(피드백에서 제안한 카테고리) / NEW(피드백 이후 새로 초과가 예상됨)
     * @param feedbackReductionWon 피드백 시점의 감축 제안액. NEW면 null
     */
    public record ActionCard(
            int categoryId,
            String categoryName,
            long reductionWon,
            String actionType,
            String currentBehavior,
            String targetBehavior,
            String contextInsight,
            double feasibilityScore,
            long monthlySavingWon,
            long annualSavingWon,
            String method,
            String feasibilityLevel,
            List<String> feasibilityReasons,
            String source,
            Long feedbackReductionWon) {}
}
