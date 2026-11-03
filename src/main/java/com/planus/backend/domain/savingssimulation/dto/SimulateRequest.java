package com.planus.backend.domain.savingssimulation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 절약 시뮬레이션 요청 DTO.
 *
 * @param yearMonth           대상 연월 (yyyy-MM)
 * @param selectedCategoryIds 시뮬레이션할 카테고리 ID 목록
 */
public record SimulateRequest(@NotNull String yearMonth, @NotEmpty List<@NotNull Integer> selectedCategoryIds) {}
