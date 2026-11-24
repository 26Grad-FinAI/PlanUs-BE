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
 */
public record SuggestResponse(
        @Schema(description = "주간 피드백 ID", example = "123") Long feedbackId,
        @Schema(description = "예산 반영 여부 (주간 피드백 전체 기준)", example = "false") boolean applied,
        List<ActionCard> actions,
        long totalPossibleReduction,
        long totalMonthlySaving,
        long totalAnnualSaving) {

    /**
     * @param reductionWon     이번 달 남은 기간에 줄일 금액 — 예산 반영 금액
     * @param monthlySavingWon 같은 습관을 한 달 내내 유지하면 절약되는 금액
     * @param annualSavingWon  monthlySavingWon × 12
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
            long annualSavingWon) {}
}
