package com.planus.backend.domain.aibudget.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 카테고리별 예산 수정 요청 DTO. */
@Schema(description = "카테고리별 예산 수정 요청")
public record BudgetCategoryUpdateRequest(
        @Schema(description = "수정할 금액", example = "50000") @NotNull @Min(0) Long amount) {}
