package com.planus.backend.domain.home.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "홈 화면 월간 재정 요약 응답")
public record HomeSummaryResponse(
        @Schema(description = "조회 월", example = "2026-10") String yearMonth,
        @Schema(description = "해당 월 총 지출 (원)", example = "850000") long totalExpense,
        @Schema(description = "예산 (원)", example = "1500000") long budget,
        @Schema(description = "예산 소진율 (%)", example = "56.7") double burnRate,
        @Schema(description = "남은 예산 (원)", example = "650000") long remainingBudget,
        @Schema(description = "수입 상세") IncomeSummary income,
        @Schema(description = "지출 상세") ExpenseSummary expense) {

    @Schema(description = "수입 상세 정보")
    public record IncomeSummary(
            @Schema(description = "수입 총액 (원)", example = "3200000") long totalIncome,
            @Schema(description = "월 소득 (원)", example = "3000000") long monthlyIncome,
            @Schema(description = "추가 소득 (원)", example = "200000") long additionalIncome) {}

    @Schema(description = "지출 상세 정보")
    public record ExpenseSummary(
            @Schema(description = "지출 총액 (원)", example = "1350000") long totalExpense,
            @Schema(description = "고정 지출 (원)", example = "500000") long fixedExpense,
            @Schema(description = "나머지 지출 (원)", example = "850000") long variableExpense) {}
}
