package com.planus.backend.domain.report.controller;

import com.planus.backend.domain.report.dto.MonthlyReportResponse;
import com.planus.backend.domain.report.service.ReportService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 리포트 관련 REST API 컨트롤러. */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * 월간 소비 리포트를 조회한다.
     *
     * @param userId    JWT 인증된 사용자 ID
     * @param yearMonth 조회 월 (yyyy-MM)
     * @return 200 OK, 총 지출/예산·카테고리별 비중·최근 6개월 추이·카테고리별 예산 대비 지출
     */
    @GetMapping("/monthly")
    public ApiResponse<MonthlyReportResponse> getMonthlyReport(
            @AuthenticationPrincipal Long userId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth yearMonth) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, reportService.getMonthlyReport(userId, yearMonth));
    }
}
