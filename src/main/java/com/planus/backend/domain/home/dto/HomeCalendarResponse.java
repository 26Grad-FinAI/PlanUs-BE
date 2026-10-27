package com.planus.backend.domain.home.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "홈 화면 캘린더 뷰 응답")
public record HomeCalendarResponse(
        @Schema(description = "조회 월", example = "2026-10") String yearMonth,
        @Schema(description = "날짜별 지출·수입 합계 목록") List<DailySummary> dailySummaries) {

    @Schema(description = "날짜별 지출·수입 합계")
    public record DailySummary(
            @Schema(description = "날짜", example = "2026-10-27") LocalDate date,
            @Schema(description = "해당일 총 지출 (원)", example = "35000") long totalExpense,
            @Schema(description = "해당일 총 수입 (원)", example = "0") long totalIncome) {}
}
