package com.planus.backend.domain.aibudget.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예산 산출 결과 응답 (AI 응답 전체)")
public record BudgetPlanResultResponse(
        @Schema(description = "AI 응답 JSON") Object aiResponse,
        @Schema(description = "상태", example = "COMPLETED") String status) {}
