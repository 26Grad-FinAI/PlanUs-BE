package com.planus.backend.domain.income.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.income.dto.IncomeRequest;
import com.planus.backend.domain.income.dto.IncomeResponse;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IncomeServiceTest {

    private ExpenseRepository expenseRepository;
    private BudgetRepository budgetRepository;
    private BudgetCategoryRepository budgetCategoryRepository;
    private IncomeService incomeService;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        budgetCategoryRepository = mock(BudgetCategoryRepository.class);
        incomeService = new IncomeService(expenseRepository, budgetRepository, budgetCategoryRepository);
    }

    @Nested
    @DisplayName("createIncome 성공")
    class CreateIncomeSuccess {

        @Test
        @DisplayName("올바른 요청 시 수입을 저장하고 예산을 업데이트한다")
        void createIncome_success_returnsResponse() {
            Expense saved = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("INCOME")
                    .amount(500000L)
                    .title("월급")
                    .expenseDate(LocalDateTime.of(2026, 8, 25, 9, 0))
                    .categoryId(1)
                    .memo("8월 급여")
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 25, 9, 0))
                    .build();
            when(expenseRepository.save(any())).thenReturn(saved);

            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(1L)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(1000000L)
                    .build();
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));

            BudgetCategory budgetCategory = BudgetCategory.builder()
                    .id(100L)
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(200000L)
                    .build();
            when(budgetCategoryRepository.findByBudgetIdAndCategoryId(10L, 1))
                    .thenReturn(Optional.of(budgetCategory));

            IncomeResponse response = incomeService.createIncome(1L, validRequest());

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.amount()).isEqualTo(500000L);
            assertThat(response.title()).isEqualTo("월급");
            assertThat(response.categoryId()).isEqualTo(1);
            assertThat(budget.getTotalBudget()).isEqualTo(1500000L);
            assertThat(budgetCategory.getAmount()).isEqualTo(700000L);
        }

        @Test
        @DisplayName("BudgetCategory가 없으면 totalBudget만 업데이트한다")
        void createIncome_noBudgetCategory_updatesTotalBudgetOnly() {
            Expense saved = Expense.builder()
                    .id(2L)
                    .userId(1L)
                    .type("INCOME")
                    .amount(100000L)
                    .title("용돈")
                    .expenseDate(LocalDateTime.of(2026, 8, 15, 10, 0))
                    .categoryId(3)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 15, 10, 0))
                    .build();
            when(expenseRepository.save(any())).thenReturn(saved);

            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(1L)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(1000000L)
                    .build();
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetIdAndCategoryId(10L, 3))
                    .thenReturn(Optional.empty());

            IncomeRequest request = new IncomeRequest(
                    100000L, "용돈", LocalDateTime.of(2026, 8, 15, 10, 0), 3, null);

            IncomeResponse response = incomeService.createIncome(1L, request);

            assertThat(response.id()).isEqualTo(2L);
            assertThat(budget.getTotalBudget()).isEqualTo(1100000L);
        }

        @Test
        @DisplayName("저장 시 ExpenseRepository.save가 호출된다")
        void createIncome_invokesSave() {
            Expense saved = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("INCOME")
                    .amount(500000L)
                    .title("월급")
                    .expenseDate(LocalDateTime.of(2026, 8, 25, 9, 0))
                    .categoryId(1)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 25, 9, 0))
                    .build();
            when(expenseRepository.save(any())).thenReturn(saved);

            Budget budget = Budget.builder()
                    .id(10L).userId(1L).yearMonth(LocalDate.of(2026, 8, 1)).totalBudget(1000000L).build();
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetIdAndCategoryId(10L, 1))
                    .thenReturn(Optional.empty());

            incomeService.createIncome(1L, validRequest());

            verify(expenseRepository).save(any(Expense.class));
        }
    }

    @Nested
    @DisplayName("createIncome 실패")
    class CreateIncomeFailure {

        @Test
        @DisplayName("카테고리 ID가 0이면 INVALID_CATEGORY 예외가 발생한다")
        void createIncome_categoryIdZero_throwsInvalidCategory() {
            IncomeRequest request = new IncomeRequest(
                    100000L, "테스트", LocalDateTime.now(), 0, null);

            assertThatThrownBy(() -> incomeService.createIncome(1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CATEGORY));
        }

        @Test
        @DisplayName("카테고리 ID가 12이면 INVALID_CATEGORY 예외가 발생한다")
        void createIncome_categoryIdTwelve_throwsInvalidCategory() {
            IncomeRequest request = new IncomeRequest(
                    100000L, "테스트", LocalDateTime.now(), 12, null);

            assertThatThrownBy(() -> incomeService.createIncome(1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CATEGORY));
        }

        @Test
        @DisplayName("해당 월 예산이 없으면 BUDGET_NOT_FOUND 예외가 발생한다")
        void createIncome_noBudget_throwsBudgetNotFound() {
            when(expenseRepository.save(any())).thenReturn(Expense.builder()
                    .id(1L).userId(1L).type("INCOME").amount(500000L).title("월급")
                    .expenseDate(LocalDateTime.of(2026, 8, 25, 9, 0)).categoryId(1)
                    .recurring(false).planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 25, 9, 0)).build());
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> incomeService.createIncome(1L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.BUDGET_NOT_FOUND));
        }
    }

    private IncomeRequest validRequest() {
        return new IncomeRequest(
                500000L,
                "월급",
                LocalDateTime.of(2026, 8, 25, 9, 0),
                1,
                "8월 급여");
    }
}
