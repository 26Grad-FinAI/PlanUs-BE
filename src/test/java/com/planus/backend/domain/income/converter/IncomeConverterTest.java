package com.planus.backend.domain.income.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.income.dto.IncomeRequest;
import com.planus.backend.domain.income.dto.IncomeResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IncomeConverterTest {

    @Nested
    @DisplayName("toExpense")
    class ToExpense {

        @Test
        @DisplayName("요청 DTO의 필드가 엔티티에 올바르게 매핑된다")
        void mapsFieldsCorrectly() {
            IncomeRequest request = new IncomeRequest(500000L, "월급", LocalDateTime.of(2026, 8, 25, 9, 0), 1, "8월 급여");

            Expense expense = IncomeConverter.toExpense(1L, request);

            assertThat(expense.getUserId()).isEqualTo(1L);
            assertThat(expense.getType()).isEqualTo("INCOME");
            assertThat(expense.getAmount()).isEqualTo(500000L);
            assertThat(expense.getTitle()).isEqualTo("월급");
            assertThat(expense.getExpenseDate()).isEqualTo(LocalDateTime.of(2026, 8, 25, 9, 0));
            assertThat(expense.getCategoryId()).isEqualTo(1);
            assertThat(expense.getMemo()).isEqualTo("8월 급여");
            assertThat(expense.isRecurring()).isFalse();
            assertThat(expense.isPlanned()).isFalse();
            assertThat(expense.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("메모가 null이면 null로 매핑된다")
        void memoNull_mappedToNull() {
            IncomeRequest request = new IncomeRequest(100000L, "용돈", LocalDateTime.now(), 2, null);

            Expense expense = IncomeConverter.toExpense(1L, request);

            assertThat(expense.getMemo()).isNull();
        }
    }

    @Nested
    @DisplayName("toIncomeResponse")
    class ToIncomeResponse {

        @Test
        @DisplayName("엔티티의 필드가 응답 DTO에 올바르게 매핑된다")
        void mapsFieldsCorrectly() {
            LocalDateTime now = LocalDateTime.of(2026, 8, 25, 9, 0);
            Expense expense = Expense.builder()
                    .id(1L)
                    .userId(1L)
                    .type("INCOME")
                    .amount(500000L)
                    .title("월급")
                    .expenseDate(now)
                    .categoryId(1)
                    .memo("8월 급여")
                    .recurring(false)
                    .planned(false)
                    .createdAt(now)
                    .build();

            IncomeResponse response = IncomeConverter.toIncomeResponse(expense);

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.amount()).isEqualTo(500000L);
            assertThat(response.title()).isEqualTo("월급");
            assertThat(response.incomeDate()).isEqualTo(now);
            assertThat(response.categoryId()).isEqualTo(1);
            assertThat(response.memo()).isEqualTo("8월 급여");
            assertThat(response.createdAt()).isEqualTo(now);
        }
    }
}
