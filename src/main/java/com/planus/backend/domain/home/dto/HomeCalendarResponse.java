package com.planus.backend.domain.home.dto;

import java.time.LocalDate;
import java.util.List;

/** 홈 화면 캘린더 뷰 응답 DTO. */
public record HomeCalendarResponse(String yearMonth, List<DailySummary> dailySummaries) {

    /** 날짜별 지출·수입 합계. */
    public record DailySummary(LocalDate date, long totalExpense, long totalIncome) {}
}
