package com.planus.backend.domain.aibudget.service;

import com.planus.backend.domain.aibudget.dto.SpendingStatsResponse;
import com.planus.backend.domain.aifeedback.core.Categories;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 월별 소비 통계 서비스. 지출·수입 총액, 카테고리별 비중, 예산 대비 사용률, 6개월 추이를 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpendingStatsService {

    private final ExpenseRepository expenseRepository;
    private final BudgetRepository budgetRepository;
    private final BudgetCategoryRepository budgetCategoryRepository;

    /**
     * 지정 월의 소비 통계를 조회한다.
     *
     * @param userId    사용자 ID
     * @param yearMonth 조회 연월 (1일 기준 LocalDate)
     * @return 월별 소비 통계 응답
     */
    public SpendingStatsResponse getMonthlyStats(Long userId, LocalDate yearMonth) {
        LocalDateTime from = yearMonth.atStartOfDay();
        LocalDateTime to = yearMonth.withDayOfMonth(yearMonth.lengthOfMonth()).atTime(LocalTime.MAX);

        long totalExpense = expenseRepository.sumExpensesByPeriod(userId, from, to);
        long totalIncome = expenseRepository.sumIncomeByPeriod(userId, from, to);

        // 예산 조회 (없으면 0 처리)
        long budgetAmount = 0;
        Map<Integer, Long> categoryBudgetMap = new HashMap<>();
        Optional<Budget> budgetOpt = budgetRepository.findByUserIdAndYearMonth(userId, yearMonth);
        if (budgetOpt.isPresent()) {
            Budget budget = budgetOpt.get();
            budgetAmount = budget.getTotalBudget();
            List<BudgetCategory> budgetCategories = budgetCategoryRepository.findByBudgetId(budget.getId());
            for (BudgetCategory bc : budgetCategories) {
                categoryBudgetMap.put(bc.getCategoryId(), bc.getAmount());
            }
        }

        BigDecimal budgetUsagePercent = budgetAmount > 0
                ? BigDecimal.valueOf(totalExpense)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(budgetAmount), 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 카테고리별 지출
        List<Object[]> categoryRows = expenseRepository.sumExpensesGroupByCategory(userId, from, to);
        List<SpendingStatsResponse.CategoryStat> categories = new ArrayList<>();
        for (Object[] row : categoryRows) {
            int categoryId = ((Number) row[0]).intValue();
            long expenseAmount = ((Number) row[1]).longValue();
            BigDecimal expensePercent = totalExpense > 0
                    ? BigDecimal.valueOf(expenseAmount)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(totalExpense), 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            long catBudget = categoryBudgetMap.getOrDefault(categoryId, 0L);
            BigDecimal catBudgetUsage = catBudget > 0
                    ? BigDecimal.valueOf(expenseAmount)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(catBudget), 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            categories.add(new SpendingStatsResponse.CategoryStat(
                    categoryId, Categories.name(categoryId), expenseAmount, expensePercent, catBudget, catBudgetUsage));
        }

        // 최근 6개월 추이 (조회 월 포함)
        List<SpendingStatsResponse.MonthlyTrend> monthlyTrend = buildMonthlyTrend(userId, yearMonth);

        return new SpendingStatsResponse(
                totalExpense, totalIncome, budgetAmount, budgetUsagePercent, categories, monthlyTrend);
    }

    private List<SpendingStatsResponse.MonthlyTrend> buildMonthlyTrend(Long userId, LocalDate yearMonth) {
        YearMonth target = YearMonth.from(yearMonth);
        YearMonth start = target.minusMonths(5);
        LocalDateTime trendFrom = start.atDay(1).atStartOfDay();
        LocalDateTime trendTo = target.atEndOfMonth().atTime(LocalTime.MAX);

        List<Object[]> rows = expenseRepository.sumExpensesGroupByMonth(userId, trendFrom, trendTo);
        Map<String, Long> dataMap = new LinkedHashMap<>();
        for (Object[] row : rows) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            dataMap.put(String.format("%04d-%02d", year, month), ((Number) row[2]).longValue());
        }

        List<SpendingStatsResponse.MonthlyTrend> trend = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            YearMonth ym = start.plusMonths(i);
            String key = String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
            trend.add(new SpendingStatsResponse.MonthlyTrend(key, dataMap.getOrDefault(key, 0L)));
        }
        return trend;
    }
}
