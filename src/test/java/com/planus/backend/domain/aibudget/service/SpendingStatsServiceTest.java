package com.planus.backend.domain.aibudget.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.planus.backend.domain.aibudget.dto.SpendingStatsResponse;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SpendingStatsServiceTest {

    private ExpenseRepository expenseRepository;
    private BudgetRepository budgetRepository;
    private BudgetCategoryRepository budgetCategoryRepository;

    private SpendingStatsService service;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        budgetCategoryRepository = mock(BudgetCategoryRepository.class);

        service = new SpendingStatsService(expenseRepository, budgetRepository, budgetCategoryRepository);
    }

    @Nested
    @DisplayName("getMonthlyStats()")
    class GetMonthlyStats {

        private final Long userId = 1L;
        private final LocalDate yearMonth = LocalDate.of(2026, 10, 1);
        private final LocalDateTime from = yearMonth.atStartOfDay();
        private final LocalDateTime to = yearMonth.withDayOfMonth(31).atTime(LocalTime.MAX);

        @Test
        @DisplayName("예산이 있는 경우 총 지출·수입·예산·사용률을 정상 반환")
        void withBudget_success() {
            when(expenseRepository.sumExpensesByPeriod(userId, from, to)).thenReturn(530800L);
            when(expenseRepository.sumIncomeByPeriod(userId, from, to)).thenReturn(2500000L);

            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(600000)
                    .build();
            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));

            BudgetCategory cat1 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(100000)
                    .build();
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of(cat1));

            List<Object[]> categoryRows = List.of(new Object[] {1, 77000L}, new Object[] {7, 154000L});
            when(expenseRepository.sumExpensesGroupByCategory(userId, from, to)).thenReturn(categoryRows);

            // 6개월 추이 (2026-05 ~ 2026-10)
            LocalDateTime trendFrom = LocalDate.of(2026, 5, 1).atStartOfDay();
            LocalDateTime trendTo = LocalDate.of(2026, 10, 31).atTime(LocalTime.MAX);
            List<Object[]> trendRows = List.of(
                    new Object[] {2026, 5, 480000L}, new Object[] {2026, 8, 510000L}, new Object[] {2026, 10, 530800L});
            when(expenseRepository.sumExpensesGroupByMonth(userId, trendFrom, trendTo))
                    .thenReturn(trendRows);

            SpendingStatsResponse result = service.getMonthlyStats(userId, yearMonth);

            assertThat(result.totalExpense()).isEqualTo(530800L);
            assertThat(result.totalIncome()).isEqualTo(2500000L);
            assertThat(result.budgetAmount()).isEqualTo(600000L);
            assertThat(result.budgetUsagePercent()).isEqualByComparingTo(new BigDecimal("88.5"));

            // 카테고리 검증
            assertThat(result.categories()).hasSize(2);
            SpendingStatsResponse.CategoryStat grocery = result.categories().get(0);
            assertThat(grocery.categoryId()).isEqualTo(1);
            assertThat(grocery.categoryName()).isEqualTo("식료품");
            assertThat(grocery.expenseAmount()).isEqualTo(77000L);
            assertThat(grocery.budgetAmount()).isEqualTo(100000L);
            assertThat(grocery.budgetUsagePercent()).isEqualByComparingTo(new BigDecimal("77.0"));

            SpendingStatsResponse.CategoryStat dining = result.categories().get(1);
            assertThat(dining.categoryId()).isEqualTo(7);
            assertThat(dining.categoryName()).isEqualTo("식대");
            assertThat(dining.budgetAmount()).isEqualTo(0L);
            assertThat(dining.budgetUsagePercent()).isEqualByComparingTo(BigDecimal.ZERO);

            // 6개월 추이 검증 (데이터 없는 월은 0)
            assertThat(result.monthlyTrend()).hasSize(6);
            assertThat(result.monthlyTrend().get(0).month()).isEqualTo("2026-05");
            assertThat(result.monthlyTrend().get(0).totalExpense()).isEqualTo(480000L);
            assertThat(result.monthlyTrend().get(1).month()).isEqualTo("2026-06");
            assertThat(result.monthlyTrend().get(1).totalExpense()).isEqualTo(0L);
            assertThat(result.monthlyTrend().get(2).month()).isEqualTo("2026-07");
            assertThat(result.monthlyTrend().get(2).totalExpense()).isEqualTo(0L);
            assertThat(result.monthlyTrend().get(3).month()).isEqualTo("2026-08");
            assertThat(result.monthlyTrend().get(3).totalExpense()).isEqualTo(510000L);
            assertThat(result.monthlyTrend().get(4).month()).isEqualTo("2026-09");
            assertThat(result.monthlyTrend().get(4).totalExpense()).isEqualTo(0L);
            assertThat(result.monthlyTrend().get(5).month()).isEqualTo("2026-10");
            assertThat(result.monthlyTrend().get(5).totalExpense()).isEqualTo(530800L);
        }

        @Test
        @DisplayName("예산이 없는 경우에도 지출·수입 통계 정상 반환, budgetAmount=0")
        void withoutBudget_success() {
            when(expenseRepository.sumExpensesByPeriod(userId, from, to)).thenReturn(300000L);
            when(expenseRepository.sumIncomeByPeriod(userId, from, to)).thenReturn(1000000L);
            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.empty());

            List<Object[]> categoryRows = List.<Object[]>of(new Object[] {4, 50000L});
            when(expenseRepository.sumExpensesGroupByCategory(userId, from, to)).thenReturn(categoryRows);

            LocalDateTime trendFrom = LocalDate.of(2026, 5, 1).atStartOfDay();
            LocalDateTime trendTo = LocalDate.of(2026, 10, 31).atTime(LocalTime.MAX);
            when(expenseRepository.sumExpensesGroupByMonth(userId, trendFrom, trendTo))
                    .thenReturn(List.of());

            SpendingStatsResponse result = service.getMonthlyStats(userId, yearMonth);

            assertThat(result.totalExpense()).isEqualTo(300000L);
            assertThat(result.totalIncome()).isEqualTo(1000000L);
            assertThat(result.budgetAmount()).isEqualTo(0L);
            assertThat(result.budgetUsagePercent()).isEqualByComparingTo(BigDecimal.ZERO);

            // 카테고리 예산은 0
            assertThat(result.categories()).hasSize(1);
            assertThat(result.categories().get(0).budgetAmount()).isEqualTo(0L);
            assertThat(result.categories().get(0).budgetUsagePercent()).isEqualByComparingTo(BigDecimal.ZERO);

            // 6개월 추이 모두 0
            assertThat(result.monthlyTrend()).hasSize(6);
            assertThat(result.monthlyTrend()).allMatch(t -> t.totalExpense() == 0L);
        }

        @Test
        @DisplayName("지출이 없는 경우 expensePercent 0 처리")
        void noExpense_zeroPercent() {
            when(expenseRepository.sumExpensesByPeriod(userId, from, to)).thenReturn(0L);
            when(expenseRepository.sumIncomeByPeriod(userId, from, to)).thenReturn(0L);
            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.empty());
            when(expenseRepository.sumExpensesGroupByCategory(userId, from, to)).thenReturn(List.of());

            LocalDateTime trendFrom = LocalDate.of(2026, 5, 1).atStartOfDay();
            LocalDateTime trendTo = LocalDate.of(2026, 10, 31).atTime(LocalTime.MAX);
            when(expenseRepository.sumExpensesGroupByMonth(userId, trendFrom, trendTo))
                    .thenReturn(List.of());

            SpendingStatsResponse result = service.getMonthlyStats(userId, yearMonth);

            assertThat(result.totalExpense()).isEqualTo(0L);
            assertThat(result.totalIncome()).isEqualTo(0L);
            assertThat(result.categories()).isEmpty();
            assertThat(result.monthlyTrend()).hasSize(6);
        }

        @Test
        @DisplayName("카테고리별 지출 비율이 올바르게 계산된다")
        void categoryExpensePercent() {
            when(expenseRepository.sumExpensesByPeriod(userId, from, to)).thenReturn(200000L);
            when(expenseRepository.sumIncomeByPeriod(userId, from, to)).thenReturn(0L);
            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.empty());

            List<Object[]> categoryRows = List.of(new Object[] {1, 100000L}, new Object[] {4, 100000L});
            when(expenseRepository.sumExpensesGroupByCategory(userId, from, to)).thenReturn(categoryRows);

            LocalDateTime trendFrom = LocalDate.of(2026, 5, 1).atStartOfDay();
            LocalDateTime trendTo = LocalDate.of(2026, 10, 31).atTime(LocalTime.MAX);
            when(expenseRepository.sumExpensesGroupByMonth(userId, trendFrom, trendTo))
                    .thenReturn(List.of());

            SpendingStatsResponse result = service.getMonthlyStats(userId, yearMonth);

            assertThat(result.categories()).hasSize(2);
            assertThat(result.categories().get(0).expensePercent()).isEqualByComparingTo(new BigDecimal("50.0"));
            assertThat(result.categories().get(1).expensePercent()).isEqualByComparingTo(new BigDecimal("50.0"));
        }
    }
}
