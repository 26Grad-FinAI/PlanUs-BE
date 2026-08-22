package com.planus.backend.domain.home.controller;

import com.planus.backend.domain.home.dto.HomeCalendarResponse;
import com.planus.backend.domain.home.dto.HomeSummaryResponse;
import com.planus.backend.domain.home.service.HomeService;
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

/** 홈 화면 관련 REST API 컨트롤러. */
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    /**
     * 월간 재정 요약을 조회한다.
     *
     * @param userId JWT 인증된 사용자 ID
     * @param yearMonth 조회 월 (yyyy-MM)
     * @return 200 OK, 사용 금액·예산·소진율·남은 예산
     */
    @GetMapping("/summary")
    public ApiResponse<HomeSummaryResponse> getSummary(
            @AuthenticationPrincipal Long userId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth yearMonth) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, homeService.getSummary(userId, yearMonth));
    }

    /**
     * 캘린더 뷰용 날짜별 지출·수입 합계를 조회한다.
     *
     * @param userId JWT 인증된 사용자 ID
     * @param yearMonth 조회 월 (yyyy-MM)
     * @return 200 OK, 날짜별 지출·수입 합계 목록
     */
    @GetMapping("/calendar")
    public ApiResponse<HomeCalendarResponse> getCalendar(
            @AuthenticationPrincipal Long userId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth yearMonth) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, homeService.getCalendar(userId, yearMonth));
    }
}
