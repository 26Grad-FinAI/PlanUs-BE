package com.planus.backend.domain.expense.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.expense.dto.ExpenseRequest;
import com.planus.backend.domain.expense.dto.ExpenseResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExpenseConverterTest {

    @Nested
    @DisplayName("toExpense")
    class ToExpense {

        @Test
        @DisplayName("요청 DTO의 필드가 엔티티에 올바르게 매핑된다")
        void mapsFieldsCorrectly() {
            ExpenseRequest request = new ExpenseRequest(
                    15000L, "스타벅스 아메리카노", LocalDateTime.of(2026, 8, 19, 12, 0), 2, "친구랑 같이", "SATISFIED", null, null);

            Expense expense = ExpenseConverter.toExpense(1L, request);

            assertThat(expense.getUserId()).isEqualTo(1L);
            assertThat(expense.getType()).isEqualTo("EXPENSE");
            assertThat(expense.getAmount()).isEqualTo(15000L);
            assertThat(expense.getTitle()).isEqualTo("스타벅스 아메리카노");
            assertThat(expense.getExpenseDate()).isEqualTo(LocalDateTime.of(2026, 8, 19, 12, 0));
            assertThat(expense.getCategoryId()).isEqualTo(2);
            assertThat(expense.getMemo()).isEqualTo("친구랑 같이");
            assertThat(expense.getEmotion()).isEqualTo("SATISFIED");
        }

        @Test
        @DisplayName("isRecurring이 null이면 false로 매핑된다")
        void recurringNull_mappedToFalse() {
            ExpenseRequest request = new ExpenseRequest(10000L, "테스트", LocalDateTime.now(), 1, null, null, null, null);

            Expense expense = ExpenseConverter.toExpense(1L, request);

            assertThat(expense.isRecurring()).isFalse();
            assertThat(expense.isPlanned()).isFalse();
        }

        @Test
        @DisplayName("isRecurring이 true이면 true로 매핑된다")
        void recurringTrue_mappedToTrue() {
            ExpenseRequest request =
                    new ExpenseRequest(500000L, "월세", LocalDateTime.now(), 11, null, null, true, false);

            Expense expense = ExpenseConverter.toExpense(1L, request);

            assertThat(expense.isRecurring()).isTrue();
            assertThat(expense.isPlanned()).isFalse();
        }
    }

    @Nested
    @DisplayName("toExpenseResponse")
    class ToExpenseResponse {

        @Test
        @DisplayName("엔티티의 필드가 응답 DTO에 올바르게 매핑된다")
        void mapsFieldsCorrectly() {
            LocalDateTime now = LocalDateTime.of(2026, 8, 19, 12, 0);
            Expense expense = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(15000L)
                    .title("스타벅스 아메리카노")
                    .expenseDate(now)
                    .categoryId(2)
                    .memo("친구랑 같이")
                    .emotion("SATISFIED")
                    .recurring(false)
                    .planned(false)
                    .createdAt(now)
                    .build();

            ExpenseResponse response = ExpenseConverter.toExpenseResponse(expense);

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.amount()).isEqualTo(15000L);
            assertThat(response.title()).isEqualTo("스타벅스 아메리카노");
            assertThat(response.expenseDate()).isEqualTo(now);
            assertThat(response.categoryId()).isEqualTo(2);
            assertThat(response.memo()).isEqualTo("친구랑 같이");
            assertThat(response.emotion()).isEqualTo("SATISFIED");
            assertThat(response.isRecurring()).isFalse();
            assertThat(response.isPlanned()).isFalse();
            assertThat(response.createdAt()).isEqualTo(now);
        }
    }
}
