package com.planus.backend.domain.aifeedback.dto;

import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주간 AI 피드백 조회 응답.
 *
 * <p>피드백이 아직 없으면 {@code exists=false}이고 나머지 필드는 null이다. 신규·저활동 사용자는 피드백이 없는 게 정상이므로 오류로 처리하지 않는다.
 */
@Schema(description = "주간 AI 피드백 조회 응답")
public record WeeklyFeedbackResponse(
        @Schema(description = "피드백 존재 여부. false면 아래 필드는 모두 null", example = "true") boolean exists,
        @Schema(description = "분석 주 시작일(월)", example = "2026-11-09") LocalDate weekStart,
        @Schema(description = "분석 주 종료일(일)", example = "2026-11-15") LocalDate weekEnd,
        @Schema(description = "피드백 문장", example = "이번 주는 식대가 평소보다 많았어요.") String feedbackText,
        @Schema(description = "피드백 유형 (ALERT / POSITIVE / LOW_DATA)", example = "ALERT") String feedbackType,
        @Schema(description = "신뢰도 (HIGH / MEDIUM / LOW)", example = "MEDIUM") String confidence,
        @Schema(description = "생성 시각") LocalDateTime createdAt) {

    /** 피드백이 없는 상태. */
    public static WeeklyFeedbackResponse empty() {
        return new WeeklyFeedbackResponse(false, null, null, null, null, null, null);
    }

    public static WeeklyFeedbackResponse from(AiFeedback f) {
        return new WeeklyFeedbackResponse(
                true,
                f.getPeriodStart(),
                f.getPeriodEnd(),
                f.getFeedbackText(),
                f.getFeedbackType(),
                f.getConfidence(),
                f.getCreatedAt());
    }
}
