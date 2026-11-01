package com.planus.backend.domain.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "지출 상세 조회 응답")
public record ExpenseDetailResponse(
        @Schema(description = "지출 내역", example = "점심 식사") String title,
        @Schema(description = "지출 금액 (원)", example = "15000") long amount,
        @Schema(description = "구분", example = "지출") String type,
        @Schema(description = "지출 일시") LocalDateTime expenseDate,
        @Schema(description = "소비 성격", example = "SATISFIED") String emotion,
        @Schema(description = "반복 지출 여부", example = "false") boolean isRecurring,
        @Schema(description = "메모", example = "회사 근처 식당") String memo) {}
