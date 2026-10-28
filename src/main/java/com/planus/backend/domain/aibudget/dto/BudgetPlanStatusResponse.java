package com.planus.backend.domain.aibudget.dto;

import com.planus.backend.domain.aibudget.entity.BudgetPlanStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "예산 산출 상태 응답")
public record BudgetPlanStatusResponse(
        @Schema(description = "상태", example = "COMPLETED") BudgetPlanStatus status,
        @Schema(description = "요청 생성일시") LocalDateTime createdAt,
        @Schema(description = "완료일시") LocalDateTime completedAt) {}
