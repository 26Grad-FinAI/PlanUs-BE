package com.planus.backend.domain.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Schema(description = "지출 등록/수정 요청")
public record ExpenseRequest(
        @Schema(description = "지출 금액 (원)", example = "15000") @NotNull @Positive Long amount,
        @Schema(description = "지출 내역", example = "점심 식사") @NotBlank @Size(max = 255) String title,
        @Schema(description = "지출 일시", example = "2026-10-27T12:30:00") @NotNull LocalDateTime expenseDate,
        @Schema(description = "카테고리 ID", example = "1") @NotNull Integer categoryId,
        @Schema(description = "메모", example = "회사 근처 식당") String memo,
        @Schema(description = "감정 태그", example = "만족") String emotion,
        @Schema(description = "고정지출 여부", example = "false") Boolean isRecurring,
        @Schema(description = "계획지출 여부", example = "true") Boolean isPlanned) {}
