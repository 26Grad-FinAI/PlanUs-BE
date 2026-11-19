package com.planus.backend.domain.aifeedback.dto;

import com.planus.backend.domain.aifeedback.entity.Reaction;
import com.planus.backend.domain.aifeedback.entity.RejectionReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 피드백 반응 저장 요청. */
@Schema(description = "AI 피드백 반응 저장 요청")
public record FeedbackReactionRequest(
        @Schema(description = "반응 (HELPFUL 도움이 됐어요 / NOT_HELPFUL 별로예요)", example = "NOT_HELPFUL") @NotNull
                Reaction reaction,
        @Schema(
                        description = "NOT_HELPFUL일 때 필수. DONT_WANT_REDUCE(줄이고 싶지 않아요), ALREADY_KNOWN(이미 알고 있어요), "
                                + "TOO_REPETITIVE(자꾸 같은 말이에요), NOT_ACCURATE(맞지 않아요), OTHER",
                        example = "DONT_WANT_REDUCE")
                RejectionReason rejectionReason,
        @Schema(description = "반응 대상 카테고리(절약 제안된 카테고리). 사유가 DONT_WANT_REDUCE면 필수", example = "10") @Min(1) @Max(10)
                Integer categoryId) {}
