package com.planus.backend.domain.aifeedback.controller;

import com.planus.backend.domain.aifeedback.service.AiFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발·검증용 내부 엔드포인트. dev/local 프로필에서만 활성화.
 */
@Tag(name = "AI Feedback (Dev)", description = "AI 피드백 개발·검증용 내부 API (dev/local 프로필 전용)")
@RestController
@Profile({"dev", "local"})
@RequestMapping("/internal/aifeedback")
public class AiFeedbackDevController {

    private static final Logger log = LoggerFactory.getLogger(AiFeedbackDevController.class);

    private final AiFeedbackService feedbackService;

    /**
     * @param feedbackService 주간 피드백 생성 서비스
     */
    public AiFeedbackDevController(AiFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @Operation(
            summary = "주간 피드백 수동 실행",
            description =
                    """
                    주간 AI 피드백 생성을 수동으로 실행한다. 스케줄러를 기다리지 않고 검증할 때 사용한다.

                    **weekEnd 형식:** yyyy-MM-dd (예: 2026-10-25)
                    **응답 필드:**
                    - created=true: 피드백 생성 성공. id, confidence, adviceType, feedback 포함.
                    - created=false: generateWeekly가 Optional.empty를 반환한 경우 또는 오류 발생. reason 또는 error 필드에 상세 사유 포함.
                    **주의사항:**
                    - dev/local 프로필에서만 활성화된다. 운영 환경에서는 접근 불가.""")
    @GetMapping("/run-weekly")
    public Map<String, Object> runWeekly(
            @Parameter(description = "대상 사용자 ID", example = "1") @RequestParam Long userId,
            @Parameter(description = "주간 종료일", example = "2026-10-25")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate weekEnd) {
        try {
            return feedbackService
                    .generateWeekly(userId, weekEnd)
                    .<Map<String, Object>>map(fb -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("created", true);
                        response.put("id", fb.getId());
                        response.put("confidence", fb.getConfidence());
                        response.put("adviceType", fb.getAdviceType());
                        response.put("feedback", fb.getFeedbackText());
                        return response;
                    })
                    .orElse(Map.of("created", false, "reason", "생성 조건 미충족(예산 초과·이상치·카테고리 초과 없음)"));
        } catch (Exception e) {
            log.error("주간 피드백 수동 실행 실패 — userId={}, weekEnd={}", userId, weekEnd, e);
            return Map.of("created", false, "error", "피드백 생성 중 오류가 발생했습니다.");
        }
    }
}
