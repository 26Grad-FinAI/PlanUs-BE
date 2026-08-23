package com.planus.backend.domain.income.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
            when(budgetCategoryRepository.findByBudgetIdAndCategoryId(10L, 1)).thenReturn(Optional.of(budgetCategory));

            IncomeResponse response = incomeService.createIncome(1L, validRequest());

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.amount()).isEqualTo(500000L);
            assertThat(response.title()).isEqualTo("월급");
            assertThat(response.categoryId()).isEqualTo(1);
            assertThat(budget.getTotalBudget()).isEqualTo(1500000L);
            assertThat(budgetCategory.getAmount()).isEqualTo(700000L);
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
            when(budgetCategoryRepository.findByBudgetIdAndCategoryId(10L, 1)).thenReturn(Optional.of(budgetCategory));

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
            IncomeRequest request = new IncomeRequest(100000L, "테스트", LocalDateTime.now(), 0, null);

            assertThatThrownBy(() -> incomeService.createIncome(1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CATEGORY));
        }

        @Test
        @DisplayName("카테고리 ID가 12이면 INVALID_CATEGORY 예외가 발생한다")
        void createIncome_categoryIdTwelve_throwsInvalidCategory() {
            IncomeRequest request = new IncomeRequest(100000L, "테스트", LocalDateTime.now(), 12, null);

            assertThatThrownBy(() -> incomeService.createIncome(1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CATEGORY));
        }

        @Test
        @DisplayName("해당 월 예산이 없으면 BUDGET_NOT_FOUND 예외가 발생한다")
        void createIncome_noBudget_throwsBudgetNotFound() {
            when(expenseRepository.save(any()))
                    .thenReturn(Expense.builder()
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
                            .build());
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> incomeService.createIncome(1L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.BUDGET_NOT_FOUND));
        }

        @Test
        @DisplayName("BudgetCategory가 없으면 BUDGET_CATEGORY_NOT_FOUND 예외가 발생한다")
        void createIncome_noBudgetCategory_throwsBudgetCategoryNotFound() {
            when(expenseRepository.save(any()))
                    .thenReturn(Expense.builder()
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
                            .build());

            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(1L)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(1000000L)
                    .build();
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetIdAndCategoryId(10L, 1)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> incomeService.createIncome(1L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.BUDGET_CATEGORY_NOT_FOUND));
        }

        @Test
        @DisplayName("수입 금액이 너무 크면 BUDGET_OVERFLOW 예외가 발생한다")
        void createIncome_overflow_throwsBudgetOverflow() {
            when(expenseRepository.save(any()))
                    .thenReturn(Expense.builder()
                            .id(1L)
                            .userId(1L)
                            .type("INCOME")
                            .amount(Long.MAX_VALUE)
                            .title("큰 수입")
                            .expenseDate(LocalDateTime.of(2026, 8, 25, 9, 0))
                            .categoryId(1)
                            .recurring(false)
                            .planned(false)
                            .createdAt(LocalDateTime.of(2026, 8, 25, 9, 0))
                            .build());

            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(1L)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(1L)
                    .build();
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));

            BudgetCategory budgetCategory = BudgetCategory.builder()
                    .id(100L)
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(1L)
                    .build();
            when(budgetCategoryRepository.findByBudgetIdAndCategoryId(10L, 1)).thenReturn(Optional.of(budgetCategory));

            IncomeRequest request =
                    new IncomeRequest(Long.MAX_VALUE, "큰 수입", LocalDateTime.of(2026, 8, 25, 9, 0), 1, null);

            assertThatThrownBy(() -> incomeService.createIncome(1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.BUDGET_OVERFLOW));
        }
    }

    @Nested
    @DisplayName("updateIncome 성공")
    class UpdateIncomeSuccess {

        @Test
        @DisplayName("올바른 요청 시 수입을 수정하고 예산을 조정한다")
        void updateIncome_success_returnsResponse() {
            Expense existing = Expense.builder()
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
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(1L)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(1500000L)
                    .build();
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));

            BudgetCategory budgetCategory = BudgetCategory.builder()
                    .id(100L)
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(700000L)
                    .build();
            when(budgetCategoryRepository.findByBudgetIdAndCategoryId(10L, 1)).thenReturn(Optional.of(budgetCategory));

            IncomeRequest updateRequest =
                    new IncomeRequest(600000L, "월급 수정", LocalDateTime.of(2026, 8, 25, 9, 0), 1, "수정된 메모");

            IncomeResponse response = incomeService.updateIncome(1L, 1L, updateRequest);

            assertThat(response.amount()).isEqualTo(600000L);
            assertThat(response.title()).isEqualTo("월급 수정");
            assertThat(response.memo()).isEqualTo("수정된 메모");
            // 예산: 1500000 - 500000(기존) + 600000(새) = 1600000
            assertThat(budget.getTotalBudget()).isEqualTo(1600000L);
            // 카테고리 예산: 700000 - 500000(기존) + 600000(새) = 800000
            assertThat(budgetCategory.getAmount()).isEqualTo(800000L);
        }

        @Test
        @DisplayName("예산이 없으면 예산 조정을 스킵하고 수입만 수정한다")
        void updateIncome_noBudget_skipsAdjustment() {
            Expense existing = Expense.builder()
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
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.empty());

            IncomeRequest updateRequest =
                    new IncomeRequest(600000L, "월급 수정", LocalDateTime.of(2026, 8, 25, 9, 0), 1, null);

            IncomeResponse response = incomeService.updateIncome(1L, 1L, updateRequest);

            assertThat(response.amount()).isEqualTo(600000L);
            assertThat(response.title()).isEqualTo("월급 수정");
        }
    }

    @Nested
    @DisplayName("updateIncome 실패")
    class UpdateIncomeFailure {

        @Test
        @DisplayName("존재하지 않는 수입이면 INCOME_NOT_FOUND 예외가 발생한다")
        void updateIncome_notFound_throwsIncomeNotFound() {
            when(expenseRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> incomeService.updateIncome(1L, 999L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INCOME_NOT_FOUND));
        }

        @Test
        @DisplayName("다른 사용자의 수입이면 FORBIDDEN 예외가 발생한다")
        void updateIncome_otherUser_throwsForbidden() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(2L)
                    .type("INCOME")
                    .amount(500000L)
                    .title("월급")
                    .expenseDate(LocalDateTime.of(2026, 8, 25, 9, 0))
                    .categoryId(1)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 25, 9, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> incomeService.updateIncome(1L, 1L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("EXPENSE 타입을 수입 API로 수정하면 BAD_REQUEST 예외가 발생한다")
        void updateIncome_expenseType_throwsBadRequest() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> incomeService.updateIncome(1L, 1L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.BAD_REQUEST));
        }

        @Test
        @DisplayName("카테고리 ID가 범위 밖이면 INVALID_CATEGORY 예외가 발생한다")
        void updateIncome_invalidCategory_throwsInvalidCategory() {
            Expense existing = Expense.builder()
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
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            IncomeRequest request = new IncomeRequest(100000L, "테스트", LocalDateTime.now(), 99, null);

            assertThatThrownBy(() -> incomeService.updateIncome(1L, 1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CATEGORY));
        }
    }

    @Nested
    @DisplayName("deleteIncome 성공")
    class DeleteIncomeSuccess {

        @Test
        @DisplayName("올바른 요청 시 수입을 삭제하고 예산에서 차감한다")
        void deleteIncome_success_deletesAndAdjustsBudget() {
            Expense existing = Expense.builder()
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
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(1L)
                    .yearMonth(LocalDate.of(2026, 8, 1))
                    .totalBudget(1500000L)
                    .build();
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.of(budget));

            BudgetCategory budgetCategory = BudgetCategory.builder()
                    .id(100L)
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(700000L)
                    .build();
            when(budgetCategoryRepository.findByBudgetIdAndCategoryId(10L, 1)).thenReturn(Optional.of(budgetCategory));

            incomeService.deleteIncome(1L, 1L);

            verify(expenseRepository).delete(existing);
            assertThat(budget.getTotalBudget()).isEqualTo(1000000L);
            assertThat(budgetCategory.getAmount()).isEqualTo(200000L);
        }

        @Test
        @DisplayName("예산이 없으면 예산 조정을 스킵하고 수입만 삭제한다")
        void deleteIncome_noBudget_skipsAdjustment() {
            Expense existing = Expense.builder()
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
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(budgetRepository.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.empty());

            incomeService.deleteIncome(1L, 1L);

            verify(expenseRepository).delete(existing);
        }
    }

    @Nested
    @DisplayName("deleteIncome 실패")
    class DeleteIncomeFailure {

        @Test
        @DisplayName("존재하지 않는 수입이면 INCOME_NOT_FOUND 예외가 발생한다")
        void deleteIncome_notFound_throwsIncomeNotFound() {
            when(expenseRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> incomeService.deleteIncome(1L, 999L))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INCOME_NOT_FOUND));
        }

        @Test
        @DisplayName("다른 사용자의 수입이면 FORBIDDEN 예외가 발생한다")
        void deleteIncome_otherUser_throwsForbidden() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(2L)
                    .type("INCOME")
                    .amount(500000L)
                    .title("월급")
                    .expenseDate(LocalDateTime.of(2026, 8, 25, 9, 0))
                    .categoryId(1)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 25, 9, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> incomeService.deleteIncome(1L, 1L))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.FORBIDDEN));

            verify(expenseRepository, never()).delete(any());
        }

        @Test
        @DisplayName("EXPENSE 타입을 수입 API로 삭제하면 BAD_REQUEST 예외가 발생한다")
        void deleteIncome_expenseType_throwsBadRequest() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> incomeService.deleteIncome(1L, 1L))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.BAD_REQUEST));

            verify(expenseRepository, never()).delete(any());
        }
    }

    private IncomeRequest validRequest() {
        return new IncomeRequest(500000L, "월급", LocalDateTime.of(2026, 8, 25, 9, 0), 1, "8월 급여");
    }
}
