package com.planus.backend.domain.aibudget.controller;

import com.planus.backend.domain.aibudget.dto.PeerComparisonResponse;
import com.planus.backend.domain.aibudget.service.BudgetPlanService;
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
