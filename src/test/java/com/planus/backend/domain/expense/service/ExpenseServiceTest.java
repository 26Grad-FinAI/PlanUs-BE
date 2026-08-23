package com.planus.backend.domain.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.expense.dto.ExpenseRequest;
import com.planus.backend.domain.expense.dto.ExpenseResponse;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExpenseServiceTest {

    private ExpenseRepository expenseRepository;
    private ExpenseService expenseService;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        expenseService = new ExpenseService(expenseRepository);
    }

    @Nested
    @DisplayName("createExpense 성공")
    class CreateExpenseSuccess {

        @Test
        @DisplayName("올바른 요청 시 지출을 저장하고 응답을 반환한다")
        void createExpense_success_returnsResponse() {
            Expense saved = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스 아메리카노")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .memo("친구랑 같이")
                    .emotion("SATISFIED")
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.save(any())).thenReturn(saved);

            ExpenseResponse response = expenseService.createExpense(1L, validRequest());

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.amount()).isEqualTo(15000L);
            assertThat(response.title()).isEqualTo("스타벅스 아메리카노");
            assertThat(response.categoryId()).isEqualTo(2);
            assertThat(response.emotion()).isEqualTo("SATISFIED");
        }

        @Test
        @DisplayName("감정 태그 없이도 저장할 수 있다")
        void createExpense_withoutEmotion_success() {
            Expense saved = Expense.builder()
                    .id(2L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(5000L)
                    .title("버스비")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 8, 0))
                    .categoryId(6)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 8, 0))
                    .build();
            when(expenseRepository.save(any())).thenReturn(saved);

            ExpenseRequest request =
                    new ExpenseRequest(5000L, "버스비", LocalDateTime.of(2026, 8, 19, 8, 0), 6, null, null, null, null);

            ExpenseResponse response = expenseService.createExpense(1L, request);

            assertThat(response.id()).isEqualTo(2L);
            assertThat(response.emotion()).isNull();
        }

        @Test
        @DisplayName("고정지출 여부가 true로 저장된다")
        void createExpense_recurring_savedAsTrue() {
            Expense saved = Expense.builder()
                    .id(3L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(500000L)
                    .title("월세")
                    .expenseDate(LocalDateTime.of(2026, 8, 1, 0, 0))
                    .categoryId(11)
                    .recurring(true)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 1, 0, 0))
                    .build();
            when(expenseRepository.save(any())).thenReturn(saved);

            ExpenseRequest request =
                    new ExpenseRequest(500000L, "월세", LocalDateTime.of(2026, 8, 1, 0, 0), 11, null, null, true, null);

            ExpenseResponse response = expenseService.createExpense(1L, request);

            assertThat(response.isRecurring()).isTrue();
            assertThat(response.isPlanned()).isFalse();
        }

        @Test
        @DisplayName("저장 시 ExpenseRepository.save가 호출된다")
        void createExpense_invokesSave() {
            Expense saved = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스 아메리카노")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.save(any())).thenReturn(saved);

            expenseService.createExpense(1L, validRequest());

            verify(expenseRepository).save(any(Expense.class));
        }
    }

    @Nested
    @DisplayName("createExpense 실패")
    class CreateExpenseFailure {

        @Test
        @DisplayName("카테고리 ID가 0이면 INVALID_CATEGORY 예외가 발생한다")
        void createExpense_categoryIdZero_throwsInvalidCategory() {
            ExpenseRequest request = new ExpenseRequest(10000L, "테스트", LocalDateTime.now(), 0, null, null, null, null);

            assertThatThrownBy(() -> expenseService.createExpense(1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CATEGORY));
        }

        @Test
        @DisplayName("카테고리 ID가 12이면 INVALID_CATEGORY 예외가 발생한다")
        void createExpense_categoryIdTwelve_throwsInvalidCategory() {
            ExpenseRequest request = new ExpenseRequest(10000L, "테스트", LocalDateTime.now(), 12, null, null, null, null);

            assertThatThrownBy(() -> expenseService.createExpense(1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CATEGORY));
        }

        @Test
        @DisplayName("유효하지 않은 감정 태그면 INVALID_EMOTION 예외가 발생한다")
        void createExpense_invalidEmotion_throwsInvalidEmotion() {
            ExpenseRequest request =
                    new ExpenseRequest(10000L, "테스트", LocalDateTime.now(), 1, null, "HAPPY", null, null);

            assertThatThrownBy(() -> expenseService.createExpense(1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_EMOTION));
        }
    }

    @Nested
    @DisplayName("updateExpense 성공")
    class UpdateExpenseSuccess {

        @Test
        @DisplayName("올바른 요청 시 지출을 수정하고 응답을 반환한다")
        void updateExpense_success_returnsResponse() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스 아메리카노")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .memo("친구랑 같이")
                    .emotion("SATISFIED")
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            ExpenseRequest updateRequest = new ExpenseRequest(
                    20000L, "투썸 케이크", LocalDateTime.of(2026, 8, 20, 14, 0), 3, "생일 케이크", "NECESSARY", true, false);

            ExpenseResponse response = expenseService.updateExpense(1L, 1L, updateRequest);

            assertThat(response.amount()).isEqualTo(20000L);
            assertThat(response.title()).isEqualTo("투썸 케이크");
            assertThat(response.categoryId()).isEqualTo(3);
            assertThat(response.emotion()).isEqualTo("NECESSARY");
            assertThat(response.isRecurring()).isTrue();
        }
    }

    @Nested
    @DisplayName("updateExpense 실패")
    class UpdateExpenseFailure {

        @Test
        @DisplayName("존재하지 않는 지출이면 EXPENSE_NOT_FOUND 예외가 발생한다")
        void updateExpense_notFound_throwsExpenseNotFound() {
            when(expenseRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> expenseService.updateExpense(1L, 999L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.EXPENSE_NOT_FOUND));
        }

        @Test
        @DisplayName("다른 사용자의 지출이면 FORBIDDEN 예외가 발생한다")
        void updateExpense_otherUser_throwsForbidden() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(2L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스 아메리카노")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> expenseService.updateExpense(1L, 1L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("INCOME 타입을 지출 API로 수정하면 BAD_REQUEST 예외가 발생한다")
        void updateExpense_incomeType_throwsBadRequest() {
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

            assertThatThrownBy(() -> expenseService.updateExpense(1L, 1L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.BAD_REQUEST));
        }

        @Test
        @DisplayName("카테고리 ID가 범위 밖이면 INVALID_CATEGORY 예외가 발생한다")
        void updateExpense_invalidCategory_throwsInvalidCategory() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스 아메리카노")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            ExpenseRequest request = new ExpenseRequest(10000L, "테스트", LocalDateTime.now(), 99, null, null, null, null);

            assertThatThrownBy(() -> expenseService.updateExpense(1L, 1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CATEGORY));
        }

        @Test
        @DisplayName("유효하지 않은 감정 태그면 INVALID_EMOTION 예외가 발생한다")
        void updateExpense_invalidEmotion_throwsInvalidEmotion() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스 아메리카노")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            ExpenseRequest request =
                    new ExpenseRequest(10000L, "테스트", LocalDateTime.now(), 1, null, "HAPPY", null, null);

            assertThatThrownBy(() -> expenseService.updateExpense(1L, 1L, request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_EMOTION));
        }
    }

    @Nested
    @DisplayName("deleteExpense 성공")
    class DeleteExpenseSuccess {

        @Test
        @DisplayName("올바른 요청 시 지출을 삭제한다")
        void deleteExpense_success_deletesExpense() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스 아메리카노")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            expenseService.deleteExpense(1L, 1L);

            verify(expenseRepository).delete(existing);
        }
    }

    @Nested
    @DisplayName("deleteExpense 실패")
    class DeleteExpenseFailure {

        @Test
        @DisplayName("존재하지 않는 지출이면 EXPENSE_NOT_FOUND 예외가 발생한다")
        void deleteExpense_notFound_throwsExpenseNotFound() {
            when(expenseRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> expenseService.deleteExpense(1L, 999L))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.EXPENSE_NOT_FOUND));
        }

        @Test
        @DisplayName("다른 사용자의 지출이면 FORBIDDEN 예외가 발생한다")
        void deleteExpense_otherUser_throwsForbidden() {
            Expense existing = Expense.builder()
                    .id(1L)
                    .userId(2L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스 아메리카노")
                    .expenseDate(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .categoryId(2)
                    .recurring(false)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 8, 19, 12, 0))
                    .build();
            when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> expenseService.deleteExpense(1L, 1L))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.FORBIDDEN));

            verify(expenseRepository, never()).delete(any());
        }

        @Test
        @DisplayName("INCOME 타입을 지출 API로 삭제하면 BAD_REQUEST 예외가 발생한다")
        void deleteExpense_incomeType_throwsBadRequest() {
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

            assertThatThrownBy(() -> expenseService.deleteExpense(1L, 1L))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.BAD_REQUEST));

            verify(expenseRepository, never()).delete(any());
        }
    }

    private ExpenseRequest validRequest() {
        return new ExpenseRequest(
                15000L, "스타벅스 아메리카노", LocalDateTime.of(2026, 8, 19, 12, 0), 2, "친구랑 같이", "SATISFIED", null, null);
    }
}
