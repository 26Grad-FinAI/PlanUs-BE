package com.planus.backend.domain.report.controller;

import com.planus.backend.domain.report.dto.MonthlyReportResponse;
import com.planus.backend.domain.report.service.ReportService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 리포트 관련 REST API 컨트롤러. */
@Tag(name = "Report", description = "월간 소비 리포트 API")
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(
            summary = "월간 리포트 조회",
            description = """
                    해당 월의 종합 소비 리포트를 조회한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **yearMonth 형식:** yyyy-MM (예: 2026-10)
                    **응답 구성:**
                    - categoryRatios: 카테고리별 지출 비중 (소비 금액 내림차순 정렬). 지출이 0이면 비율 0.0.
                    - monthlyTrends: 조회 월 포함 최근 6개월 월별 총 지출 추이. 거래가 없는 월은 0.
                    - categoryBudgets: 카테고리별 예산 대비 지출 현황. 예산 미설정 카테고리는 budget이 0.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @GetMapping("/monthly")
    public ApiResponse<MonthlyReportResponse> getMonthlyReport(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 월 (yyyy-MM)", example = "2026-10")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth yearMonth) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, reportService.getMonthlyReport(userId, yearMonth));
    }
}
