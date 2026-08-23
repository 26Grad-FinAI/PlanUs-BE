package com.planus.backend.domain.home.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 홈 화면 날짜별 상세 내역 응답 DTO. */
public record HomeDailyResponse(
        LocalDate date, long totalExpense, long totalIncome, List<TransactionDetail> transactions) {

    /** 개별 거래 상세 정보. */
    public record TransactionDetail(
            Long id,
            String type,
            long amount,
            String title,
            int categoryId,
            String memo,
            String emotion,
            boolean isRecurring,
            boolean isPlanned,
            LocalTime time) {}
}
