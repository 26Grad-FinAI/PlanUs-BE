package com.planus.backend.domain.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.home.dto.HomeCalendarResponse;
import com.planus.backend.domain.home.dto.HomeDailyResponse;
import com.planus.backend.domain.home.dto.HomeSummaryResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HomeServiceTest {

    private ExpenseRepository expenseRepository;
    private BudgetRepository budgetRepository;
    private UserAccountRepository userAccountRepository;
    private HomeService homeService;

    private static final Long USER_ID = 1L;
    private static final YearMonth YEAR_MONTH = YearMonth.of(2026, 8);

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);
        homeService = new HomeService(expenseRepository, budgetRepository, userAccountRepository);
    }

    @Nested
    @DisplayName("getSummary 성공")
    class GetSummarySuccess {

        @Test
        @DisplayName("Budget이 있으면 해당 예산으로 요약을 반환한다")
        void getSummary_withBudget_returnsSummary() {
            Budget budget = Budget.builder()
                    .id(1L)
                    .userId(USER_ID)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(2_000_000L)
                    .build();
            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any()))
                    .thenReturn(850_000L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));

            HomeSummaryResponse response = homeService.getSummary(USER_ID, YEAR_MONTH);

            assertThat(response.yearMonth()).isEqualTo("2026-08");
            assertThat(response.totalExpense()).isEqualTo(850_000L);
            assertThat(response.budget()).isEqualTo(2_000_000L);
            assertThat(response.remainingBudget()).isEqualTo(1_150_000L);
            assertThat(response.burnRate()).isEqualTo(42.5);
            verify(userAccountRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Budget이 없으면 UserAccount.availableBudget()을 사용한다")
        void getSummary_noBudget_usesUserAccountAvailableBudget() {
            UserAccount user = UserAccount.builder()
                    .id(USER_ID)
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(500_000L)
                    .monthlySavingsGoal(500_000L)
                    .build();
            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any()))
                    .thenReturn(400_000L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.empty());
            when(userAccountRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            HomeSummaryResponse response = homeService.getSummary(USER_ID, YEAR_MONTH);

            assertThat(response.budget()).isEqualTo(2_000_000L); // 3M - 500K - 500K
            assertThat(response.totalExpense()).isEqualTo(400_000L);
            assertThat(response.remainingBudget()).isEqualTo(1_600_000L);
            assertThat(response.burnRate()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("지출이 0이면 소진율 0%, 남은 예산은 예산과 동일하다")
        void getSummary_noExpense_zeroRateFullBudget() {
            Budget budget = Budget.builder()
                    .id(1L)
                    .userId(USER_ID)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(1_000_000L)
                    .build();
            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any()))
                    .thenReturn(0L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));

            HomeSummaryResponse response = homeService.getSummary(USER_ID, YEAR_MONTH);

            assertThat(response.totalExpense()).isZero();
            assertThat(response.burnRate()).isEqualTo(0.0);
            assertThat(response.remainingBudget()).isEqualTo(1_000_000L);
        }

        @Test
        @DisplayName("지출이 예산을 초과하면 남은 예산이 음수이다")
        void getSummary_overBudget_negativeRemaining() {
            Budget budget = Budget.builder()
                    .id(1L)
                    .userId(USER_ID)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(500_000L)
                    .build();
            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any()))
                    .thenReturn(700_000L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));

            HomeSummaryResponse response = homeService.getSummary(USER_ID, YEAR_MONTH);

            assertThat(response.remainingBudget()).isEqualTo(-200_000L);
            assertThat(response.burnRate()).isEqualTo(140.0);
        }

        @Test
        @DisplayName("예산이 0이면 소진율은 0.0이다")
        void getSummary_zeroBudget_burnRateZero() {
            Budget budget = Budget.builder()
                    .id(1L)
                    .userId(USER_ID)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(0L)
                    .build();
            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any()))
                    .thenReturn(100_000L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));

            HomeSummaryResponse response = homeService.getSummary(USER_ID, YEAR_MONTH);

            assertThat(response.burnRate()).isEqualTo(0.0);
            assertThat(response.remainingBudget()).isEqualTo(-100_000L);
        }
    }

    @Nested
    @DisplayName("getCalendar 성공")
    class GetCalendarSuccess {

        @Test
        @DisplayName("거래가 있는 날짜별로 지출·수입 합계를 반환한다")
        void getCalendar_withTransactions_returnsDailySummaries() {
            List<Expense> expenses = List.of(
                    Expense.builder()
                            .id(1L)
                            .userId(USER_ID)
                            .type("EXPENSE")
                            .amount(15_000L)
                            .expenseDate(LocalDateTime.of(2026, 8, 1, 12, 0))
                            .build(),
                    Expense.builder()
                            .id(2L)
                            .userId(USER_ID)
                            .type("EXPENSE")
                            .amount(30_000L)
                            .expenseDate(LocalDateTime.of(2026, 8, 1, 18, 0))
                            .build(),
                    Expense.builder()
                            .id(3L)
                            .userId(USER_ID)
                            .type("INCOME")
                            .amount(3_000_000L)
                            .expenseDate(LocalDateTime.of(2026, 8, 2, 9, 0))
                            .build(),
                    Expense.builder()
                            .id(4L)
                            .userId(USER_ID)
                            .type("EXPENSE")
                            .amount(12_000L)
                            .expenseDate(LocalDateTime.of(2026, 8, 2, 12, 0))
                            .build());
            when(expenseRepository.findByUserIdAndExpenseDateBetween(eq(USER_ID), any(), any()))
                    .thenReturn(expenses);

            HomeCalendarResponse response = homeService.getCalendar(USER_ID, YEAR_MONTH);

            assertThat(response.yearMonth()).isEqualTo("2026-08");
            assertThat(response.dailySummaries()).hasSize(2);

            assertThat(response.dailySummaries().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(response.dailySummaries().get(0).totalExpense()).isEqualTo(45_000L);
            assertThat(response.dailySummaries().get(0).totalIncome()).isZero();

            assertThat(response.dailySummaries().get(1).date()).isEqualTo(LocalDate.of(2026, 8, 2));
            assertThat(response.dailySummaries().get(1).totalExpense()).isEqualTo(12_000L);
            assertThat(response.dailySummaries().get(1).totalIncome()).isEqualTo(3_000_000L);
        }

        @Test
        @DisplayName("거래가 없으면 빈 목록을 반환한다")
        void getCalendar_noTransactions_returnsEmptyList() {
            when(expenseRepository.findByUserIdAndExpenseDateBetween(eq(USER_ID), any(), any()))
                    .thenReturn(Collections.emptyList());

            HomeCalendarResponse response = homeService.getCalendar(USER_ID, YEAR_MONTH);

            assertThat(response.yearMonth()).isEqualTo("2026-08");
            assertThat(response.dailySummaries()).isEmpty();
        }

        @Test
        @DisplayName("날짜 순서대로 정렬되어 반환된다")
        void getCalendar_returnsSortedByDate() {
            List<Expense> expenses = List.of(
                    Expense.builder()
                            .id(1L)
                            .userId(USER_ID)
                            .type("EXPENSE")
                            .amount(10_000L)
                            .expenseDate(LocalDateTime.of(2026, 8, 15, 12, 0))
                            .build(),
                    Expense.builder()
                            .id(2L)
                            .userId(USER_ID)
                            .type("EXPENSE")
                            .amount(20_000L)
                            .expenseDate(LocalDateTime.of(2026, 8, 3, 12, 0))
                            .build());
            when(expenseRepository.findByUserIdAndExpenseDateBetween(eq(USER_ID), any(), any()))
                    .thenReturn(expenses);

            HomeCalendarResponse response = homeService.getCalendar(USER_ID, YEAR_MONTH);

            assertThat(response.dailySummaries().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 3));
            assertThat(response.dailySummaries().get(1).date()).isEqualTo(LocalDate.of(2026, 8, 15));
        }
    }

    @Nested
    @DisplayName("getDailyDetail 성공")
    class GetDailyDetailSuccess {

        private static final LocalDate DATE = LocalDate.of(2026, 8, 22);

        @Test
        @DisplayName("해당 날짜의 거래 상세 내역과 합계를 반환한다")
        void getDailyDetail_withTransactions_returnsDetail() {
            List<Expense> expenses = List.of(
                    Expense.builder()
                            .id(1L)
                            .userId(USER_ID)
                            .type("EXPENSE")
                            .amount(15_000L)
                            .title("점심 식사")
                            .categoryId(1)
                            .emotion("NECESSARY")
                            .recurring(false)
                            .planned(false)
                            .expenseDate(LocalDateTime.of(2026, 8, 22, 12, 30))
                            .build(),
                    Expense.builder()
                            .id(2L)
                            .userId(USER_ID)
                            .type("INCOME")
                            .amount(3_000_000L)
                            .title("월급")
                            .categoryId(1)
                            .recurring(true)
                            .planned(true)
                            .expenseDate(LocalDateTime.of(2026, 8, 22, 9, 0))
                            .build());
            when(expenseRepository.findByUserIdAndExpenseDateBetween(eq(USER_ID), any(), any()))
                    .thenReturn(expenses);

            HomeDailyResponse response = homeService.getDailyDetail(USER_ID, DATE);

            assertThat(response.date()).isEqualTo(DATE);
            assertThat(response.totalExpense()).isEqualTo(15_000L);
            assertThat(response.totalIncome()).isEqualTo(3_000_000L);
            assertThat(response.transactions()).hasSize(2);

            assertThat(response.transactions().get(0).id()).isEqualTo(1L);
            assertThat(response.transactions().get(0).type()).isEqualTo("EXPENSE");
            assertThat(response.transactions().get(0).title()).isEqualTo("점심 식사");
            assertThat(response.transactions().get(0).emotion()).isEqualTo("NECESSARY");

            assertThat(response.transactions().get(1).id()).isEqualTo(2L);
            assertThat(response.transactions().get(1).type()).isEqualTo("INCOME");
            assertThat(response.transactions().get(1).isRecurring()).isTrue();
        }

        @Test
        @DisplayName("거래가 없으면 합계 0과 빈 목록을 반환한다")
        void getDailyDetail_noTransactions_returnsEmpty() {
            when(expenseRepository.findByUserIdAndExpenseDateBetween(eq(USER_ID), any(), any()))
                    .thenReturn(Collections.emptyList());

            HomeDailyResponse response = homeService.getDailyDetail(USER_ID, DATE);

            assertThat(response.date()).isEqualTo(DATE);
            assertThat(response.totalExpense()).isZero();
            assertThat(response.totalIncome()).isZero();
            assertThat(response.transactions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getSummary 실패")
    class GetSummaryFailure {

        @Test
        @DisplayName("Budget이 없고 사용자도 없으면 NOT_FOUND 예외가 발생한다")
        void getSummary_noBudgetNoUser_throwsNotFound() {
            when(expenseRepository.sumExpensesByPeriod(eq(USER_ID), any(), any()))
                    .thenReturn(0L);
            when(budgetRepository.findByUserIdAndYearMonth(USER_ID, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.empty());
            when(userAccountRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> homeService.getSummary(USER_ID, YEAR_MONTH))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND));
        }
    }
}
