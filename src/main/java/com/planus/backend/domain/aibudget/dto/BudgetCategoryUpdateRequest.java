package com.planus.backend.domain.aibudget.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 카테고리 예산 일괄 저장 요청 DTO. */
@Schema(description = "카테고리 예산 일괄 저장 요청")
public record BudgetCategoryUpdateRequest(
        @Schema(description = "수정된 카테고리 목록") @NotEmpty List<@NotNull @Valid CategoryAmount> categories) {

    @Schema(description = "카테고리별 금액")
    public record CategoryAmount(
            @Schema(description = "카테고리 ID", example = "1") @NotNull Integer categoryId,
            @Schema(description = "수정 금액", example = "50000") @NotNull @Min(0) Long amount) {}
}
