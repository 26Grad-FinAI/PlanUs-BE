package com.planus.backend.domain.aifeedback.controller;

import com.planus.backend.domain.aifeedback.dto.FeedbackReactionRequest;
import com.planus.backend.domain.aifeedback.dto.FeedbackReactionResponse;
import com.planus.backend.domain.aifeedback.dto.WeeklyFeedbackResponse;
import com.planus.backend.domain.aifeedback.service.AiFeedbackQueryService;
import com.planus.backend.domain.aifeedback.service.FeedbackReactionService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 피드백 조회 API. 피드백 생성은 주간 배치가 맡는다. */
@Tag(name = "AI Feedback", description = "주간 AI 소비 피드백 조회 API")
@RestController
@RequestMapping("/api/ai-feedback")
@RequiredArgsConstructor
public class AiFeedbackController {

    private final AiFeedbackQueryService queryService;
    private final FeedbackReactionService reactionService;

    @Operation(
            summary = "주간 AI 피드백 조회",
            description =
                    """
                    주간 배치(매주 월요일 09:00)가 저장한 AI 피드백을 조회한다. 조회만 하며 LLM을 호출하지 않는다.

                    - weekEnd를 생략하면 가장 최근 주의 피드백을 반환한다.
                    - weekEnd는 분석 주의 마지막 날(일요일)이다.
                    - 해당 피드백이 없으면 오류 대신 `exists=false`로 응답한다 (신규·저활동 사용자는 피드백이 없는 게 정상).""")
    @GetMapping("/weekly/latest")
    public ApiResponse<WeeklyFeedbackResponse> getWeekly(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "분석 주의 마지막 날(일요일), 생략 시 가장 최근 주", example = "2026-11-15")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate weekEnd) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, queryService.getWeekly(userId, weekEnd));
    }

    @Operation(
            summary = "AI 피드백 반응 저장",
            description =
                    """
                    피드백에 대한 반응(도움이 됐어요 / 별로예요)을 저장한다.

                    - `NOT_HELPFUL`이면 `rejectionReason`이 필수다.
                    - 사유가 `DONT_WANT_REDUCE`(줄이고 싶지 않아요)면 줄이고 싶지 않은 카테고리 `categoryId`가 필수다. 조회 응답의 `actionCategoryIds` 중에서 고른다.
                    - 같은 피드백·카테고리에 다시 반응하면 덮어쓴다.
                    - 저장한 반응은 다음 분석부터 절약 제안과 개인화에 반영된다.""")
    @PostMapping("/{feedbackId}/reaction")
    public ApiResponse<FeedbackReactionResponse> saveReaction(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "피드백 ID (조회 응답의 feedbackId)", example = "42") @PathVariable Long feedbackId,
            @Valid @RequestBody FeedbackReactionRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, reactionService.save(userId, feedbackId, request));
    }
}
