package com.planus.backend.domain.aibudget.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 또래 소비 비교 응답 DTO. 사용자 카테고리별 지출과 또래 평균 지출을 비교하여 반환한다. */
@Schema(description = "또래 소비 비교 응답")
public record PeerComparisonResponse(
        @Schema(description = "비교 대상 또래 인원수") int peerGroupSize,
        @Schema(description = "내 총 소비액") long totalSpending,
        @Schema(description = "카테고리별 또래 비교 목록") List<CategoryComparison> categories) {

    @Schema(description = "카테고리별 또래 비교 정보")
    public record CategoryComparison(
            @Schema(description = "카테고리 ID") int categoryId,
            @Schema(description = "카테고리명") String categoryName,
            @Schema(description = "내 지출 금액") long myAmount,
            @Schema(description = "또래 평균 지출 금액") long peerAmount,
            @Schema(description = "차이 (myAmount - peerAmount, 양수면 또래보다 많이 씀)") long difference) {}
}
