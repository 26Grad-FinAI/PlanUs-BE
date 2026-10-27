package com.planus.backend.domain.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "지출 응답")
public record ExpenseResponse(
        @Schema(description = "지출 ID", example = "1") Long id,
        @Schema(description = "지출 금액 (원)", example = "15000") long amount,
        @Schema(description = "지출 내역", example = "점심 식사") String title,
        @Schema(description = "지출 일시") LocalDateTime expenseDate,
        @Schema(description = "카테고리 ID", example = "1") Integer categoryId,
        @Schema(description = "메모", example = "회사 근처 식당") String memo,
        @Schema(description = "감정 태그", example = "SATISFIED") String emotion,
        @Schema(description = "고정지출 여부", example = "false") boolean isRecurring,
        @Schema(description = "계획지출 여부", example = "true") boolean isPlanned,
        @Schema(description = "등록일시") LocalDateTime createdAt) {}
