package com.planus.backend.domain.savingssimulation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 시뮬레이션 결과 예산 반영 요청 DTO.
 *
 * @param yearMonth           대상 연월 (yyyy-MM)
 * @param feedbackId          제안 조회 응답의 주간 피드백 ID
 * @param selectedCategoryIds 예산에 반영할 카테고리 ID 목록
 * @param confirmedReductionWon 사용자가 확인한 감축액 합계. null이면 금액 검사를 하지 않는다
 */
public record ApplyRequest(
        @NotNull String yearMonth,
        @Schema(description = "제안 조회 응답의 주간 피드백 ID", example = "123") @NotNull @Positive Long feedbackId,
        @NotEmpty List<@NotNull Integer> selectedCategoryIds,
        @Schema(
                        description =
                                "사용자가 시뮬레이션에서 확인한 감축액 합계(simulate 응답의 reductionTotal). 보내면 현재 계산과 다를 때 반영하지 않고 재확인을 요구한다",
                        example = "60000")
                Long confirmedReductionWon) {

    /** 확인 금액 없이 보내는 형식(검사 생략). */
    public ApplyRequest(String yearMonth, Long feedbackId, List<Integer> selectedCategoryIds) {
        this(yearMonth, feedbackId, selectedCategoryIds, null);
    }
}
