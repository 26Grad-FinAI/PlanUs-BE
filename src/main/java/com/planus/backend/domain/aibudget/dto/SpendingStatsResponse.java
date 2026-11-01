package com.planus.backend.domain.aibudget.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** 월별 소비 통계 응답 DTO. 지출·수입 총액, 카테고리별 비중, 예산 대비 사용률, 6개월 추이를 반환한다. */
@Schema(description = "월별 소비 통계 응답")
public record SpendingStatsResponse(
        @Schema(description = "월 총 지출액") long totalExpense,
        @Schema(description = "월 총 수입액") long totalIncome,
        @Schema(description = "월 예산 총액 (예산 미설정 시 0)") long budgetAmount,
        @Schema(description = "예산 대비 사용률 (%) (예산 미설정 시 0)") BigDecimal budgetUsagePercent,
        @Schema(description = "카테고리별 소비 통계 목록") List<CategoryStat> categories,
        @Schema(description = "최근 6개월 소비 추이") List<MonthlyTrend> monthlyTrend) {

    @Schema(description = "카테고리별 소비 통계")
    public record CategoryStat(
            @Schema(description = "카테고리 ID") int categoryId,
            @Schema(description = "카테고리명") String categoryName,
            @Schema(description = "카테고리 지출 금액") long expenseAmount,
            @Schema(description = "전체 지출 대비 비율 (%)") BigDecimal expensePercent,
            @Schema(description = "카테고리 예산 금액 (예산 미설정 시 0)") long budgetAmount,
            @Schema(description = "카테고리 예산 대비 사용률 (%) (예산 미설정 시 0)") BigDecimal budgetUsagePercent) {}

    @Schema(description = "월별 소비 추이")
    public record MonthlyTrend(
            @Schema(description = "연월 (yyyy-MM)", example = "2026-05") String month,
            @Schema(description = "해당 월 총 지출액") long totalExpense) {}
}
