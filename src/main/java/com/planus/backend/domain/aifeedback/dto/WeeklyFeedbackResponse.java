package com.planus.backend.domain.aifeedback.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 주간 AI 피드백 조회 응답.
 *
 * <p>피드백이 아직 없으면 {@code exists=false}이고 나머지 필드는 null이다. 신규·저활동 사용자는 피드백이 없는 게 정상이므로 오류로 처리하지 않는다.
 */
@Schema(description = "주간 AI 피드백 조회 응답")
public record WeeklyFeedbackResponse(
        @Schema(description = "피드백 존재 여부. false면 아래 필드는 모두 null", example = "true") boolean exists,
        @Schema(description = "피드백 ID. 반응 저장 API에 쓴다", example = "42") Long feedbackId,
        @Schema(description = "분석 주 시작일(월)", example = "2026-11-09") LocalDate weekStart,
        @Schema(description = "분석 주 종료일(일)", example = "2026-11-15") LocalDate weekEnd,
        @Schema(description = "피드백 문장", example = "이번 주는 식대가 평소보다 많았어요.") String feedbackText,
        @Schema(description = "피드백 유형 (ALERT / POSITIVE / LOW_DATA)", example = "ALERT") String feedbackType,
        @Schema(description = "신뢰도 (HIGH / MEDIUM / LOW)", example = "MEDIUM") String confidence,
        @Schema(description = "생성 시각") LocalDateTime createdAt,
        @Schema(description = "절약을 제안한 카테고리 ID 목록. '줄이고 싶지 않아요' 반응의 categoryId는 이 중에서 고른다", example = "[10, 1]")
                List<Integer> actionCategoryIds) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 피드백이 없는 상태. */
    public static WeeklyFeedbackResponse empty() {
        return new WeeklyFeedbackResponse(false, null, null, null, null, null, null, null, List.of());
    }

    public static WeeklyFeedbackResponse from(AiFeedback f) {
        return new WeeklyFeedbackResponse(
                true,
                f.getId(),
                f.getPeriodStart(),
                f.getPeriodEnd(),
                f.getFeedbackText(),
                f.getFeedbackType(),
                f.getConfidence(),
                f.getCreatedAt(),
                actionCategoryIds(f.getPayload()));
    }

    /** 저장된 분석 결과(payload)의 절약 액션 카테고리. 읽을 수 없으면 빈 목록. */
    public static List<Integer> actionCategoryIds(String payload) {
        List<Integer> ids = new ArrayList<>();
        if (payload == null || payload.isBlank()) return ids;
        try {
            JsonNode actions = MAPPER.readTree(payload).path("actions");
            for (JsonNode a : actions)
                if (a.hasNonNull("categoryId")) ids.add(a.get("categoryId").asInt());
        } catch (Exception e) {
            return List.of();
        }
        return ids;
    }
}
