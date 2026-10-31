package com.planus.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "월 고정지출 항목")
public record FixedExpenseItem(
        @Schema(description = "지출 항목명", example = "월세") @NotBlank String name,
        @Schema(description = "금액 (원)", example = "500000") @NotNull @PositiveOrZero Long amount) {}
