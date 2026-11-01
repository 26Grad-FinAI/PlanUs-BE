package com.planus.backend.domain.aibudget.controller;

import com.planus.backend.domain.aibudget.dto.PeerComparisonResponse;
import com.planus.backend.domain.aibudget.dto.SpendingStatsResponse;
import com.planus.backend.domain.aibudget.service.BudgetPlanService;
import com.planus.backend.domain.aibudget.service.SpendingStatsService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 소비 통계 API. */
@Tag(name = "Spending Stats", description = "소비 통계 API")
@RestController
@RequestMapping("/api/spending-stats")
@RequiredArgsConstructor
public class SpendingStatsController {

    private final BudgetPlanService budgetPlanService;
    private final SpendingStatsService spendingStatsService;

    @Operation(summary = "월별 소비 통계 조회", description = "해당 월의 지출·수입 총액, 카테고리별 비중, 예산 대비 사용률, 최근 6개월 소비 추이를 반환한다.")
    @GetMapping("/monthly")
    public ApiResponse<SpendingStatsResponse> getMonthlyStats(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 연월 (yyyy-MM)", example = "2026-10")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth yearMonth) {

        LocalDate ym = yearMonth.atDay(1);
        SpendingStatsResponse stats = spendingStatsService.getMonthlyStats(userId, ym);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, stats);
    }

    @Operation(summary = "또래 소비 비교 조회", description = "사용자의 카테고리별 지출과 또래 평균 지출을 비교한다. 9개 카테고리 전체를 반환한다.")
    @GetMapping("/peer-comparison")
    public ApiResponse<PeerComparisonResponse> getPeerComparison(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 연월 (yyyy-MM)", example = "2026-10")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth yearMonth) {

        LocalDate ym = yearMonth.atDay(1);
        PeerComparisonResponse comparison = budgetPlanService.getPeerComparison(userId, ym);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, comparison);
    }
}
