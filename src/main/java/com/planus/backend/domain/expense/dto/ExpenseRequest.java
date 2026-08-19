package com.planus.backend.domain.expense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

/** 지출 등록 요청 DTO. */
public record ExpenseRequest(
        @NotNull @Positive Long amount,
        @NotBlank String title,
        @NotNull LocalDateTime expenseDate,
        @NotNull Integer categoryId,
        String memo,
        String emotion,
        Boolean isRecurring,
        Boolean isPlanned) {}
