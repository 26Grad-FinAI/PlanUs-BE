package com.planus.backend.domain.savingssimulation.dto;

import java.util.List;

/**
 * 절약 제안 목록 응답 DTO.
 *
 * @param actions                  절약 제안 카드 목록
 * @param totalPossibleReduction   총 절약 가능 금액 (원)
 */
public record SuggestResponse(List<ActionCard> actions, long totalPossibleReduction) {

    public record ActionCard(
            int categoryId,
            String categoryName,
            long reductionWon,
            String actionType,
            String currentBehavior,
            String targetBehavior,
            String contextInsight,
            double feasibilityScore) {}
}
