package com.planus.backend.domain.income.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record IncomeRequest(
        @NotNull @Positive Long amount,
        @NotBlank @Size(max = 255) String title,
        @NotNull LocalDateTime incomeDate,
        @NotNull Integer categoryId,
        String memo) {}
