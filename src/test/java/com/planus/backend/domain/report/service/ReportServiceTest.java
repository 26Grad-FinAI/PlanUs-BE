package com.planus.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.report.dto.MonthlyReportResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReportServiceTest {

    private ExpenseRepository expenseRepository;
    private BudgetRepository budgetRepository;
    private BudgetCategoryRepository budgetCategoryRepository;
    private UserAccountRepository userAccountRepository;
    private ReportService reportService;

    private static final Long USER_ID = 1L;
    private static final YearMonth YEAR_MONTH = YearMonth.of(2026, 8);
    private static final LocalDate YEAR_MONTH_DATE = LocalDate.of(2026, 8, 1);

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        budgetCategoryRepository = mock(BudgetCategoryRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);
        reportService = new ReportService(
                expenseRepository, budgetRepository, budgetCategoryRepository, userAccountRepository);
    }

    @Nested
    @DisplayName("getMonthlyReport 성공")
    class GetMonthlyReportSuccess {

        @Test
        @DisplayName("Budget이 있으면 해당 예산으로 리포트를 반환한다")
        void getMonthlyReport_withBudget_returnsReport() {
            Budget budget = Budget.builder()
                    .id(1L)
                    .userId(USER_ID)
                    .yearMonth(YEAR_MONTH_DATE)
                    .totalBudget(800_000L)
                    .build();
            List<BudgetCategory> budgetCategories = List.of(
                    BudgetCategory.builder().id(1L).budgetId(1L).categoryId(1).amount(400_000L).build(),
                    BudgetCategory.builder().id(2L).budgetId(1L).categoryId(10).amount(300_000L).build());

            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any())).thenReturn(520_000L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, YEAR_MONTH_DATE))
                    .thenReturn(Optional.of(budget));
            when(expenseRepository.sumExpensesGroupByCategory(eq(USER_ID), any(), any()))
                    .thenReturn(List.<Object[]>of(new Object[]{1, 300_000L}, new Object[]{10, 220_000L}));
            when(expenseRepository.sumExpensesGroupByMonth(eq(USER_ID), any(), any()))
                    .thenReturn(List.<Object[]>of(new Object[]{2026, 8, 520_000L}));
            when(budgetCategoryRepository.findByBudgetId(1L)).thenReturn(budgetCategories);

            MonthlyReportResponse response = reportService.getMonthlyReport(USER_ID, YEAR_MONTH);

            assertThat(response.yearMonth()).isEqualTo("2026-08");
            assertThat(response.totalExpense()).isEqualTo(520_000L);
            assertThat(response.totalBudget()).isEqualTo(800_000L);
            verify(userAccountRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Budget이 없으면 UserAccount.availableBudget()을 총 예산으로 사용한다")
        void getMonthlyReport_noBudget_usesAvailableBudget() {
            UserAccount user = UserAccount.builder()
                    .id(USER_ID)
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(500_000L)
                    .monthlySavingsGoal(500_000L)
                    .build();

            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any())).thenReturn(200_000L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, YEAR_MONTH_DATE))
                    .thenReturn(Optional.empty());
            when(userAccountRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(expenseRepository.sumExpensesGroupByCategory(eq(USER_ID), any(), any()))
                    .thenReturn(List.<Object[]>of(new Object[]{1, 200_000L}));
            when(expenseRepository.sumExpensesGroupByMonth(eq(USER_ID), any(), any()))
                    .thenReturn(Collections.emptyList());

            MonthlyReportResponse response = reportService.getMonthlyReport(USER_ID, YEAR_MONTH);

            assertThat(response.totalBudget()).isEqualTo(2_000_000L); // 3M - 500K - 500K
            assertThat(response.categoryBudgets()).hasSize(1);
            assertThat(response.categoryBudgets().get(0).budget()).isZero(); // 예산 설정 없음
        }

        @Test
        @DisplayName("카테고리별 비중은 전체 지출 대비 퍼센테이지로 계산된다")
        void getMonthlyReport_categoryRatios_calculatedCorrectly() {
            Budget budget = Budget.builder()
                    .id(1L).userId(USER_ID).yearMonth(YEAR_MONTH_DATE).totalBudget(1_000_000L).build();

            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any())).thenReturn(400_000L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, YEAR_MONTH_DATE))
                    .thenReturn(Optional.of(budget));
            when(expenseRepository.sumExpensesGroupByCategory(eq(USER_ID), any(), any()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{1, 200_000L},  // 50%
                            new Object[]{6, 100_000L},  // 25%
                            new Object[]{8, 100_000L})); // 25%
            when(expenseRepository.sumExpensesGroupByMonth(eq(USER_ID), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(budgetCategoryRepository.findByBudgetId(1L)).thenReturn(Collections.emptyList());

            MonthlyReportResponse response = reportService.getMonthlyReport(USER_ID, YEAR_MONTH);

            // 지출 큰 순 정렬
            assertThat(response.categoryRatios()).hasSize(3);
            assertThat(response.categoryRatios().get(0).categoryId()).isEqualTo(1);
            assertThat(response.categoryRatios().get(0).ratio()).isEqualTo(50.0);
            assertThat(response.categoryRatios().get(1).ratio()).isEqualTo(25.0);
            assertThat(response.categoryRatios().get(2).ratio()).isEqualTo(25.0);
        }

        @Test
        @DisplayName("지출이 없으면 카테고리 비중은 빈 목록을 반환한다")
        void getMonthlyReport_noExpense_emptyCategoryRatios() {
            Budget budget = Budget.builder()
                    .id(1L).userId(USER_ID).yearMonth(YEAR_MONTH_DATE).totalBudget(1_000_000L).build();
            List<BudgetCategory> budgetCategories = List.of(
                    BudgetCategory.builder().id(1L).budgetId(1L).categoryId(1).amount(500_000L).build());

            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any())).thenReturn(0L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, YEAR_MONTH_DATE))
                    .thenReturn(Optional.of(budget));
            when(expenseRepository.sumExpensesGroupByCategory(eq(USER_ID), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(expenseRepository.sumExpensesGroupByMonth(eq(USER_ID), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(budgetCategoryRepository.findByBudgetId(1L)).thenReturn(budgetCategories);

            MonthlyReportResponse response = reportService.getMonthlyReport(USER_ID, YEAR_MONTH);

            assertThat(response.categoryRatios()).isEmpty();
            // 예산은 있으나 지출 없음 → expense=0
            assertThat(response.categoryBudgets()).hasSize(1);
            assertThat(response.categoryBudgets().get(0).expense()).isZero();
            assertThat(response.categoryBudgets().get(0).budget()).isEqualTo(500_000L);
        }

        @Test
        @DisplayName("최근 6개월 추이는 지출 없는 달을 0으로 채워 6개를 반환한다")
        void getMonthlyReport_monthlyTrends_alwaysReturns6Months() {
            Budget budget = Budget.builder()
                    .id(1L).userId(USER_ID).yearMonth(YEAR_MONTH_DATE).totalBudget(1_000_000L).build();

            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any())).thenReturn(0L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, YEAR_MONTH_DATE))
                    .thenReturn(Optional.of(budget));
            when(expenseRepository.sumExpensesGroupByCategory(eq(USER_ID), any(), any()))
                    .thenReturn(Collections.emptyList());
            // 2026-06, 2026-08 두 달만 지출 있음
            when(expenseRepository.sumExpensesGroupByMonth(eq(USER_ID), any(), any()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{2026, 6, 300_000L},
                            new Object[]{2026, 8, 520_000L}));
            when(budgetCategoryRepository.findByBudgetId(1L)).thenReturn(Collections.emptyList());

            MonthlyReportResponse response = reportService.getMonthlyReport(USER_ID, YEAR_MONTH);

            assertThat(response.monthlyTrends()).hasSize(6);
            assertThat(response.monthlyTrends().get(0).yearMonth()).isEqualTo("2026-03");
            assertThat(response.monthlyTrends().get(0).totalExpense()).isZero();  // 지출 없는 달
            assertThat(response.monthlyTrends().get(3).yearMonth()).isEqualTo("2026-06");
            assertThat(response.monthlyTrends().get(3).totalExpense()).isEqualTo(300_000L);
            assertThat(response.monthlyTrends().get(5).yearMonth()).isEqualTo("2026-08");
            assertThat(response.monthlyTrends().get(5).totalExpense()).isEqualTo(520_000L);
        }

        @Test
        @DisplayName("예산 없는 카테고리에 지출이 있으면 budget=0으로 categoryBudgets에 포함된다")
        void getMonthlyReport_expenseWithoutBudgetCategory_includedWithZeroBudget() {
            Budget budget = Budget.builder()
                    .id(1L).userId(USER_ID).yearMonth(YEAR_MONTH_DATE).totalBudget(500_000L).build();
            // 카테고리 1만 예산 설정
            List<BudgetCategory> budgetCategories = List.of(
                    BudgetCategory.builder().id(1L).budgetId(1L).categoryId(1).amount(300_000L).build());

            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any())).thenReturn(350_000L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, YEAR_MONTH_DATE))
                    .thenReturn(Optional.of(budget));
            // 카테고리 1, 8 지출 (카테고리 8은 예산 없음)
            when(expenseRepository.sumExpensesGroupByCategory(eq(USER_ID), any(), any()))
                    .thenReturn(List.<Object[]>of(new Object[]{1, 300_000L}, new Object[]{8, 50_000L}));
            when(expenseRepository.sumExpensesGroupByMonth(eq(USER_ID), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(budgetCategoryRepository.findByBudgetId(1L)).thenReturn(budgetCategories);

            MonthlyReportResponse response = reportService.getMonthlyReport(USER_ID, YEAR_MONTH);

            // categoryId 순 정렬: 1, 8
            assertThat(response.categoryBudgets()).hasSize(2);
            assertThat(response.categoryBudgets().get(0).categoryId()).isEqualTo(1);
            assertThat(response.categoryBudgets().get(0).budget()).isEqualTo(300_000L);
            assertThat(response.categoryBudgets().get(0).expense()).isEqualTo(300_000L);
            assertThat(response.categoryBudgets().get(1).categoryId()).isEqualTo(8);
            assertThat(response.categoryBudgets().get(1).budget()).isZero();
            assertThat(response.categoryBudgets().get(1).expense()).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("카테고리 이름이 올바르게 매핑된다")
        void getMonthlyReport_categoryName_mappedCorrectly() {
            Budget budget = Budget.builder()
                    .id(1L).userId(USER_ID).yearMonth(YEAR_MONTH_DATE).totalBudget(500_000L).build();

            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any())).thenReturn(100_000L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, YEAR_MONTH_DATE))
                    .thenReturn(Optional.of(budget));
            when(expenseRepository.sumExpensesGroupByCategory(eq(USER_ID), any(), any()))
                    .thenReturn(List.<Object[]>of(new Object[]{1, 100_000L}));
            when(expenseRepository.sumExpensesGroupByMonth(eq(USER_ID), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(budgetCategoryRepository.findByBudgetId(1L)).thenReturn(Collections.emptyList());

            MonthlyReportResponse response = reportService.getMonthlyReport(USER_ID, YEAR_MONTH);

            assertThat(response.categoryRatios().get(0).categoryName()).isEqualTo("식료품");
        }
    }

    @Nested
    @DisplayName("getMonthlyReport 실패")
    class GetMonthlyReportFailure {

        @Test
        @DisplayName("Budget이 없고 사용자도 없으면 NOT_FOUND 예외가 발생한다")
        void getMonthlyReport_noBudgetNoUser_throwsNotFound() {
            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any())).thenReturn(0L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, YEAR_MONTH_DATE))
                    .thenReturn(Optional.empty());
            when(userAccountRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reportService.getMonthlyReport(USER_ID, YEAR_MONTH))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode())
                                    .isEqualTo(GeneralErrorCode.NOT_FOUND));
        }
    }
}
