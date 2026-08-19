package com.planus.backend.domain.expense.dto;

import java.time.LocalDateTime;

/** 지출 등록 응답 DTO. */
public record ExpenseResponse(
        Long id,
        long amount,
        String title,
        LocalDateTime expenseDate,
        Integer categoryId,
        String memo,
        String emotion,
        boolean isRecurring,
        boolean isPlanned,
        LocalDateTime createdAt) {}
