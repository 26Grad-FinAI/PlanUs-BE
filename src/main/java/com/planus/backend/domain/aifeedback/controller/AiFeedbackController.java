package com.planus.backend.domain.aifeedback.controller;

import com.planus.backend.domain.aifeedback.dto.WeeklyFeedbackResponse;
import com.planus.backend.domain.aifeedback.service.AiFeedbackQueryService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
}
