package com.planus.backend.domain.savingssimulation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 절약 시뮬레이션 요청 DTO.
 *
 * @param yearMonth           대상 연월 (yyyy-MM)
 * @param feedbackId          제안 조회 응답의 주간 피드백 ID
 * @param selectedCategoryIds 시뮬레이션할 카테고리 ID 목록
 */
public record SimulateRequest(
        @NotNull String yearMonth,
        @Schema(description = "제안 조회 응답의 주간 피드백 ID", example = "123") @NotNull @Positive Long feedbackId,
        @NotEmpty List<@NotNull Integer> selectedCategoryIds) {}
