package com.planus.backend.domain.aibudget.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aibudget.client.AiBudgetClient;
import com.planus.backend.domain.aibudget.dto.BudgetCategoryUpdateRequest;
import com.planus.backend.domain.aibudget.dto.BudgetPlanResultResponse;
import com.planus.backend.domain.aibudget.dto.BudgetPlanStatusResponse;
import com.planus.backend.domain.aibudget.dto.BudgetSummaryResponse;
import com.planus.backend.domain.aibudget.entity.BudgetPlanRequest;
import com.planus.backend.domain.aibudget.entity.BudgetPlanStatus;
import com.planus.backend.domain.aibudget.repository.BudgetPlanRequestRepository;
import com.planus.backend.domain.aibudget.service.BudgetPlanService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** AI 예산 산출 요청·상태·결과 조회 API. */
@Tag(name = "Budget Plan", description = "AI 예산 산출 요청·상태·결과 조회 API")
@RestController
@RequestMapping("/api/budget-plan")
@RequiredArgsConstructor
public class BudgetPlanController {

    private final BudgetPlanService budgetPlanService;
    private final BudgetPlanRequestRepository budgetPlanRequestRepository;
    private final AiBudgetClient aiBudgetClient;
    private final ObjectMapper objectMapper;

    @Operation(summary = "AI 서버 Health Check", description = "AI 서버를 깨우는 용도. trigger 전에 호출하면 cold start 대기를 줄일 수 있다.")
    @GetMapping("/health")
    public ApiResponse<String> healthCheck() {
        String result = aiBudgetClient.healthCheck();
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, result);
    }

    @Operation(summary = "예산 산출 수동 트리거", description = "AI 예산 산출을 수동으로 요청한다. 프로필 완료 필수.")
    @PostMapping("/trigger")
    public ApiResponse<Void> trigger(@AuthenticationPrincipal Long userId) {
        budgetPlanService.triggerBudgetPlan(userId);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK);
    }

    @Operation(summary = "예산 산출 상태 조회", description = "해당 월의 예산 산출 요청 상태를 조회한다.")
    @GetMapping("/status")
    public ApiResponse<BudgetPlanStatusResponse> getStatus(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 연월 (yyyy-MM)", example = "2026-10")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth yearMonth) {

        LocalDate ym = yearMonth.atDay(1);
        BudgetPlanRequest request = budgetPlanRequestRepository
                .findByUserIdAndYearMonth(userId, ym)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.BUDGET_PLAN_NOT_FOUND));

        return ApiResponse.onSuccess(
                GeneralSuccessCode.OK,
                new BudgetPlanStatusResponse(request.getStatus(), request.getCreatedAt(), request.getCompletedAt()));
    }

    @Operation(summary = "예산 산출 결과 조회", description = "해당 월의 AI 예산 산출 결과(전체 AI 응답)를 조회한다.")
    @GetMapping("/result")
    public ApiResponse<BudgetPlanResultResponse> getResult(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 연월 (yyyy-MM)", example = "2026-10")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth yearMonth) {

        LocalDate ym = yearMonth.atDay(1);
        BudgetPlanRequest request = budgetPlanRequestRepository
                .findByUserIdAndYearMonth(userId, ym)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.BUDGET_PLAN_NOT_FOUND));

        if (request.getStatus() != BudgetPlanStatus.COMPLETED) {
            throw new GeneralException(GeneralErrorCode.BUDGET_PLAN_NOT_COMPLETED);
        }

        Object parsed;
        try {
            parsed = objectMapper.readValue(request.getAiResponse(), Object.class);
        } catch (Exception e) {
            parsed = request.getAiResponse();
        }

        return ApiResponse.onSuccess(
                GeneralSuccessCode.OK,
                new BudgetPlanResultResponse(parsed, request.getStatus().name()));
    }

    @Operation(summary = "예산 요약 조회", description = "AI 제안 예산과 사용자 수정 예산을 비교하여 요약 정보를 반환한다.")
    @GetMapping("/summary")
    public ApiResponse<BudgetSummaryResponse> getSummary(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 연월 (yyyy-MM)", example = "2026-10")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth yearMonth) {

        LocalDate ym = yearMonth.atDay(1);
        BudgetSummaryResponse summary = budgetPlanService.getBudgetSummary(userId, ym);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, summary);
    }

    @Operation(summary = "카테고리 예산 일괄 저장", description = "프론트에서 수정한 카테고리별 금액을 일괄 저장한다. 총 예산은 자동 재계산된다.")
    @PutMapping("/categories")
    public ApiResponse<BudgetSummaryResponse> saveCategories(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 연월 (yyyy-MM)", example = "2026-10")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth yearMonth,
            @Valid @RequestBody BudgetCategoryUpdateRequest request) {

        LocalDate ym = yearMonth.atDay(1);
        BudgetSummaryResponse summary = budgetPlanService.saveCategories(userId, ym, request.categories());
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, summary);
    }
}
