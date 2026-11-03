package com.planus.backend.domain.savingssimulation.dto;

import java.util.List;

/**
 * 예산 반영 결과 응답 DTO.
 *
 * @param applied            반영 성공 여부
 * @param updatedCategories  변경된 카테고리 목록
 * @param newTotalBudget     변경 후 총 예산 (원)
 * @param monthlySavings     변경 후 월간 저축 가능액 (원)
 */
public record ApplyResponse(
        boolean applied, List<CategoryChange> updatedCategories, long newTotalBudget, long monthlySavings) {

    public record CategoryChange(int categoryId, long before, long after) {}
}
