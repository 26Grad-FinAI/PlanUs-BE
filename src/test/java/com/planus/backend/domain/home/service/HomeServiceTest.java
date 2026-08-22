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
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.home.dto.HomeSummaryResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDate;
import java.time.YearMonth;
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
