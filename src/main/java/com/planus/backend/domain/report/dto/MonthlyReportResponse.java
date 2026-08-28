package com.planus.backend.domain.report.dto;

import java.util.List;

/** 월간 리포트 응답 DTO. */
public record MonthlyReportResponse(
        String yearMonth,
        long totalExpense,
        long totalBudget,
        List<CategoryRatio> categoryRatios,
        List<MonthlyTrend> monthlyTrends,
        List<CategoryBudgetStatus> categoryBudgets) {

    /** 카테고리별 소비 금액과 전체 지출 대비 비중. */
    public record CategoryRatio(int categoryId, String categoryName, long amount, double ratio) {}

    /** 최근 6개월 월별 총 지출 추이. */
    public record MonthlyTrend(String yearMonth, long totalExpense) {}

    /** 카테고리별 예산 대비 지출 현황. */
    public record CategoryBudgetStatus(int categoryId, String categoryName, long budget, long expense) {}
}
