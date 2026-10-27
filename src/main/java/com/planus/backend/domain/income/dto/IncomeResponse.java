package com.planus.backend.domain.income.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "수입 응답")
public record IncomeResponse(
        @Schema(description = "수입 ID", example = "1") Long id,
        @Schema(description = "수입 금액 (원)", example = "3000000") long amount,
        @Schema(description = "수입 내역", example = "월급") String title,
        @Schema(description = "수입 일시") LocalDateTime incomeDate,
        @Schema(description = "카테고리 ID", example = "1") Integer categoryId,
        @Schema(description = "메모", example = "10월 급여") String memo,
        @Schema(description = "등록일시") LocalDateTime createdAt) {}
