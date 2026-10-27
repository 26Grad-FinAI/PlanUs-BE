package com.planus.backend.domain.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "월간 소비 리포트 응답")
public record MonthlyReportResponse(
        @Schema(description = "조회 월", example = "2026-10") String yearMonth,
        @Schema(description = "해당 월 총 지출 (원)", example = "850000") long totalExpense,
        @Schema(description = "해당 월 총 예산 (원)", example = "1500000") long totalBudget,
        @Schema(description = "카테고리별 소비 비중 목록") List<CategoryRatio> categoryRatios,
        @Schema(description = "최근 6개월 월별 총 지출 추이") List<MonthlyTrend> monthlyTrends,
        @Schema(description = "카테고리별 예산 대비 지출 현황") List<CategoryBudgetStatus> categoryBudgets) {

    @Schema(description = "카테고리별 소비 금액과 비중")
    public record CategoryRatio(
            @Schema(description = "카테고리 ID", example = "1") int categoryId,
            @Schema(description = "카테고리명", example = "식비") String categoryName,
            @Schema(description = "소비 금액 (원)", example = "350000") long amount,
            @Schema(description = "전체 지출 대비 비율 (%)", example = "41.2") double ratio) {}

    @Schema(description = "월별 총 지출 추이")
    public record MonthlyTrend(
            @Schema(description = "월", example = "2026-10") String yearMonth,
            @Schema(description = "총 지출 (원)", example = "850000") long totalExpense) {}

    @Schema(description = "카테고리별 예산 대비 지출 현황")
    public record CategoryBudgetStatus(
            @Schema(description = "카테고리 ID", example = "1") int categoryId,
            @Schema(description = "카테고리명", example = "식비") String categoryName,
            @Schema(description = "예산 (원)", example = "400000") long budget,
            @Schema(description = "지출 (원)", example = "350000") long expense) {}
}
