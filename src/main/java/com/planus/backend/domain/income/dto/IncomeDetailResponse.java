package com.planus.backend.domain.income.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "수입 상세 조회 응답")
public record IncomeDetailResponse(
        @Schema(description = "수입 내역", example = "월급") String title,
        @Schema(description = "수입 금액 (원)", example = "3000000") long amount,
        @Schema(description = "구분", example = "수입") String type,
        @Schema(description = "카테고리 ID", example = "1") Integer categoryId,
        @Schema(description = "메모", example = "10월 급여") String memo) {}
