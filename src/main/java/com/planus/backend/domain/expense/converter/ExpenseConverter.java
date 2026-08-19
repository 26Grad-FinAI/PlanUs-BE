package com.planus.backend.domain.expense.converter;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.expense.dto.ExpenseRequest;
import com.planus.backend.domain.expense.dto.ExpenseResponse;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Expense 엔티티 ↔ DTO 변환 유틸리티. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ExpenseConverter {

    /** 요청 DTO → Expense 엔티티. */
    public static Expense toExpense(Long userId, ExpenseRequest request) {
        return Expense.builder()
                .userId(userId)
                .type("EXPENSE")
                .amount(request.amount())
                .title(request.title())
                .expenseDate(request.expenseDate())
                .categoryId(request.categoryId())
                .memo(request.memo())
                .emotion(request.emotion())
                .recurring(request.isRecurring() != null && request.isRecurring())
                .planned(request.isPlanned() != null && request.isPlanned())
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** Expense 엔티티 → 응답 DTO. */
    public static ExpenseResponse toExpenseResponse(Expense expense) {
        return new ExpenseResponse(
                expense.getId(),
                expense.getAmount(),
                expense.getTitle(),
                expense.getExpenseDate(),
                expense.getCategoryId(),
                expense.getMemo(),
                expense.getEmotion(),
                expense.isRecurring(),
                expense.isPlanned(),
                expense.getCreatedAt());
    }
}
