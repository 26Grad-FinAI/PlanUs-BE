package com.planus.backend.domain.home.controller;

import com.planus.backend.domain.home.dto.HomeCalendarResponse;
import com.planus.backend.domain.home.dto.HomeDailyResponse;
import com.planus.backend.domain.home.dto.HomeRecentResponse;
import com.planus.backend.domain.home.dto.HomeSummaryResponse;
import com.planus.backend.domain.home.service.HomeService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 홈 화면 관련 REST API 컨트롤러. */
@Tag(name = "Home", description = "홈 화면 월간요약·캘린더·일별상세·최근내역 API")
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    @Operation(
            summary = "월간 재정 요약 조회",
            description =
                    """
                    해당 월의 총 지출·예산·소진율·남은 예산을 조회한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **yearMonth 형식:** yyyy-MM (예: 2026-10)
                    **계산 규칙:**
                    - 예산: 해당 월 Budget이 있으면 사용, 없으면 사용자 가용예산(소득-고정지출-저축목표) 사용.
                    - 소진율(burnRate): (총 지출 / 예산) x 100, 소수점 1자리 반올림. 예산이 0 이하이면 0.0.
                    - 남은 예산(remainingBudget): 예산 - 총 지출 (음수 가능).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/summary")
    public ApiResponse<HomeSummaryResponse> getSummary(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 월 (yyyy-MM)", example = "2026-10")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth yearMonth) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, homeService.getSummary(userId, yearMonth));
    }

    @Operation(
            summary = "캘린더 뷰 조회",
            description =
                    """
                    해당 월의 날짜별 지출·수입 합계를 조회한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **yearMonth 형식:** yyyy-MM (예: 2026-10)
                    **주의사항:**
                    - 거래가 없는 날짜는 목록에 포함되지 않는다.
                    - 날짜 오름차순 정렬.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/calendar")
    public ApiResponse<HomeCalendarResponse> getCalendar(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 월 (yyyy-MM)", example = "2026-10")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth yearMonth) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, homeService.getCalendar(userId, yearMonth));
    }

    @Operation(
            summary = "일별 상세 내역 조회",
            description =
                    """
                    특정 날짜의 지출·수입 거래 상세 내역을 조회한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **date 형식:** yyyy-MM-dd (예: 2026-10-27)
                    **주의사항:**
                    - 거래가 없으면 transactions가 빈 배열로 반환된다.
                    - type 필드는 "EXPENSE" 또는 "INCOME".""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/daily")
    public ApiResponse<HomeDailyResponse> getDailyDetail(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 날짜 (yyyy-MM-dd)", example = "2026-10-27")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM-dd")
                    LocalDate date) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, homeService.getDailyDetail(userId, date));
    }

    @Operation(
            summary = "최근 거래 내역 조회",
            description =
                    """
                    선택한 월의 수입·지출 거래를 거래 일시와 ID 내림차순으로 조회한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **조회 방법:**
                    - 첫 요청에서는 cursor를 생략하고, 다음 요청에는 같은 yearMonth와 응답의 nextCursor를 전달한다.
                    - 금액은 부호를 포함한다. 지출은 음수, 수입은 양수다.
                    - 거래가 없으면 transactions는 빈 배열이고, 다음 페이지가 없으면 nextCursor는 null이다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "잘못된 조회 월, 페이지 크기 또는 커서",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/recent")
    public ApiResponse<HomeRecentResponse> getRecentTransactions(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회 월 (yyyy-MM)", example = "2026-10")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth yearMonth,
            @Parameter(description = "페이지 크기 (1~100)", example = "20") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "직전 응답의 다음 페이지 커서") @RequestParam(required = false) String cursor) {
        return ApiResponse.onSuccess(
                GeneralSuccessCode.OK, homeService.getRecentTransactions(userId, yearMonth, size, cursor));
    }
}
