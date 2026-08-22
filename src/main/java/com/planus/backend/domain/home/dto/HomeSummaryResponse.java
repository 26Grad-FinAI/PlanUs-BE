package com.planus.backend.domain.home.dto;

/** 홈 화면 월간 재정 요약 응답 DTO. */
public record HomeSummaryResponse(
        String yearMonth, long totalExpense, long budget, double burnRate, long remainingBudget) {}
