package com.planus.backend.domain.aibudget.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** 예산 요약 응답 DTO. AI 제안 예산과 사용자 수정 예산을 비교하여 반환한다. */
@Schema(description = "예산 요약 응답")
public record BudgetSummaryResponse(
        @Schema(description = "AI 분석 요약") String aiAnalysis,
        @Schema(description = "AI 제안 총 예산") long aiTotalBudget,
        @Schema(description = "사용자 총 예산") long userTotalBudget,
        @Schema(description = "예산 차이 (사용자 - AI)") long budgetDifference,
        @Schema(description = "카테고리별 예산 목록") List<CategoryBudget> categories) {

    @Schema(description = "카테고리별 예산 정보")
    public record CategoryBudget(
            @Schema(description = "카테고리 ID") int categoryId,
            @Schema(description = "카테고리명") String categoryName,
            @Schema(description = "AI 제안 금액") long aiAmount,
            @Schema(description = "사용자 금액") long userAmount,
            @Schema(description = "차이 (사용자 - AI)") long difference,
            @Schema(description = "총 예산 대비 비율 (%)") BigDecimal percentage,
            @Schema(description = "사용자 수정 여부") boolean userModified) {}
}
