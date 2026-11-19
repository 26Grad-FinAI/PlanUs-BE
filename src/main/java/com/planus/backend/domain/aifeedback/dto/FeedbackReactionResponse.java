package com.planus.backend.domain.aifeedback.dto;

import com.planus.backend.domain.aifeedback.entity.Reaction;
import com.planus.backend.domain.aifeedback.entity.RejectionReason;
import com.planus.backend.domain.aifeedback.entity.UserReaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 저장된 피드백 반응. */
@Schema(description = "AI 피드백 반응 저장 결과")
public record FeedbackReactionResponse(
        @Schema(description = "반응 ID") Long reactionId,
        @Schema(description = "피드백 ID") Long feedbackId,
        @Schema(description = "반응") Reaction reaction,
        @Schema(description = "거절 사유 (HELPFUL이면 null)") RejectionReason rejectionReason,
        @Schema(description = "반응 대상 카테고리 (없으면 null)") Integer categoryId,
        @Schema(description = "저장 시각") LocalDateTime createdAt) {

    public static FeedbackReactionResponse from(UserReaction r) {
        return new FeedbackReactionResponse(
                r.getId(),
                r.getFeedbackId(),
                r.getReaction(),
                r.getRejectionReason(),
                r.getCategoryId(),
                r.getCreatedAt());
    }
}
