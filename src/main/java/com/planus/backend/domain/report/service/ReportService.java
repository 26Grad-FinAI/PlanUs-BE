package com.planus.backend.domain.report.service;

import com.planus.backend.domain.aifeedback.core.Categories;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.report.dto.MonthlyReportResponse;
import com.planus.backend.domain.report.dto.MonthlyReportResponse.CategoryBudgetStatus;
import com.planus.backend.domain.report.dto.MonthlyReportResponse.CategoryRatio;
import com.planus.backend.domain.report.dto.MonthlyReportResponse.MonthlyTrend;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 월간 리포트 비즈니스 로직. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final ExpenseRepository expenseRepository;
    private final BudgetRepository budgetRepository;
    private final BudgetCategoryRepository budgetCategoryRepository;
    private final UserAccountRepository userAccountRepository;

    /**
     * 월간 리포트를 조회한다.
     *
     * @param userId    사용자 ID
     * @param yearMonth 조회 월
     * @return 총 지출/예산, 카테고리별 비중, 최근 6개월 추이, 카테고리별 예산 대비 지출
     */
    public MonthlyReportResponse getMonthlyReport(Long userId, YearMonth yearMonth) {
        LocalDateTime from = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime to = yearMonth.atEndOfMonth().atTime(LocalTime.MAX);

        // 1. 이번달 총 지출
        long totalExpense = expenseRepository.sumExpensesByPeriod(userId, from, to);

        // 2. 이번달 총 예산 (Budget 없으면 UserAccount.availableBudget() 폴백)
        LocalDate yearMonthDate = yearMonth.atDay(1);
        Optional<Budget> budgetOpt = budgetRepository.findByUserIdAndYearMonth(userId, yearMonthDate);
        long totalBudget = budgetOpt.map(Budget::getTotalBudget).orElseGet(() -> userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND))
                .availableBudget());

        // 3. 카테고리별 지출 집계
        List<Object[]> categoryRows = expenseRepository.sumExpensesGroupByCategory(userId, from, to);
        Map<Integer, Long> categoryExpenseMap = categoryRows.stream()
                .collect(Collectors.toMap(row -> (Integer) row[0], row -> ((Number) row[1]).longValue()));

        // 4. 카테고리별 소비 비중 (지출 큰 순 정렬)
        List<CategoryRatio> categoryRatios = categoryExpenseMap.entrySet().stream()
                .map(entry -> {
                    double ratio = totalExpense > 0
                            ? BigDecimal.valueOf(entry.getValue())
                                    .multiply(BigDecimal.valueOf(100))
                                    .divide(BigDecimal.valueOf(totalExpense), 1, RoundingMode.HALF_UP)
                                    .doubleValue()
                            : 0.0;
                    return new CategoryRatio(entry.getKey(), Categories.name(entry.getKey()), entry.getValue(), ratio);
                })
                .sorted(Comparator.comparingDouble(CategoryRatio::ratio).reversed())
                .toList();

        // 5. 최근 6개월 월별 총 지출 추이
        LocalDateTime trendFrom = yearMonth.minusMonths(5).atDay(1).atStartOfDay();
        List<Object[]> trendRows = expenseRepository.sumExpensesGroupByMonth(userId, trendFrom, to);
        List<MonthlyTrend> monthlyTrends = buildMonthlyTrends(yearMonth, trendRows);

        // 6. 카테고리별 예산 대비 지출 (예산 없는 카테고리는 budget=0)
        List<BudgetCategory> budgetCategories = budgetOpt
                .map(b -> budgetCategoryRepository.findByBudgetId(b.getId()))
                .orElse(List.of());
        List<CategoryBudgetStatus> categoryBudgets = buildCategoryBudgets(budgetCategories, categoryExpenseMap);

        return new MonthlyReportResponse(
                yearMonth.toString(), totalExpense, totalBudget, categoryRatios, monthlyTrends, categoryBudgets);
    }

    private List<MonthlyTrend> buildMonthlyTrends(YearMonth target, List<Object[]> rows) {
        Map<YearMonth, Long> trendMap = rows.stream()
                .collect(Collectors.toMap(
                        row -> YearMonth.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue()),
                        row -> ((Number) row[2]).longValue()));

        return IntStream.rangeClosed(0, 5)
                .mapToObj(i -> target.minusMonths(5 - i))
                .map(ym -> new MonthlyTrend(ym.toString(), trendMap.getOrDefault(ym, 0L)))
                .toList();
    }

    private List<CategoryBudgetStatus> buildCategoryBudgets(
            List<BudgetCategory> budgetCategories, Map<Integer, Long> categoryExpenseMap) {

        Set<Integer> budgetedIds =
                budgetCategories.stream().map(BudgetCategory::getCategoryId).collect(Collectors.toSet());

        List<CategoryBudgetStatus> result = new ArrayList<>();

        // 예산이 설정된 카테고리
        budgetCategories.forEach(bc -> result.add(new CategoryBudgetStatus(
                bc.getCategoryId(),
                Categories.name(bc.getCategoryId()),
                bc.getAmount(),
                categoryExpenseMap.getOrDefault(bc.getCategoryId(), 0L))));

        // 예산 없이 지출만 있는 카테고리 (budget=0)
        categoryExpenseMap.entrySet().stream()
                .filter(e -> !budgetedIds.contains(e.getKey()))
                .forEach(e -> result.add(
                        new CategoryBudgetStatus(e.getKey(), Categories.name(e.getKey()), 0L, e.getValue())));

        result.sort(Comparator.comparingInt(CategoryBudgetStatus::categoryId));
        return result;
    }
}
