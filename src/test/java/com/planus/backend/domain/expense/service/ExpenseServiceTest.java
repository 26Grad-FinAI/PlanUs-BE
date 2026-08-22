package com.planus.backend.domain.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.expense.dto.ExpenseRequest;
import com.planus.backend.domain.expense.dto.ExpenseResponse;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDateTime;
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

    private ExpenseRequest validRequest() {
        return new ExpenseRequest(
                15000L, "스타벅스 아메리카노", LocalDateTime.of(2026, 8, 19, 12, 0), 2, "친구랑 같이", "SATISFIED", null, null);
    }
}
