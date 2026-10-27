package com.planus.backend.domain.income.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Schema(description = "수입 등록/수정 요청")
public record IncomeRequest(
        @Schema(description = "수입 금액 (원)", example = "3000000") @NotNull @Positive Long amount,
        @Schema(description = "수입 내역", example = "월급") @NotBlank @Size(max = 255) String title,
        @Schema(description = "수입 일시", example = "2026-10-25T09:00:00") @NotNull LocalDateTime incomeDate,
        @Schema(description = "카테고리 ID", example = "1") @NotNull Integer categoryId,
        @Schema(description = "메모", example = "10월 급여") String memo) {}
