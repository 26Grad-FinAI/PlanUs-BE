package com.planus.backend.domain.savingssimulation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 시뮬레이션 결과 예산 반영 요청 DTO.
 *
 * @param yearMonth           대상 연월 (yyyy-MM)
 * @param selectedCategoryIds 예산에 반영할 카테고리 ID 목록
 */
public record ApplyRequest(@NotNull String yearMonth, @NotEmpty List<Integer> selectedCategoryIds) {}
