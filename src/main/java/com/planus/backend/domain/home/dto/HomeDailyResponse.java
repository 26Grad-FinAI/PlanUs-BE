package com.planus.backend.domain.home.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Schema(description = "홈 화면 날짜별 상세 내역 응답")
public record HomeDailyResponse(
        @Schema(description = "조회 날짜", example = "2026-10-27") LocalDate date,
        @Schema(description = "해당일 총 지출 (원)", example = "35000") long totalExpense,
        @Schema(description = "해당일 총 수입 (원)", example = "0") long totalIncome,
        @Schema(description = "거래 상세 목록") List<TransactionDetail> transactions) {

    @Schema(description = "개별 거래 상세 정보")
    public record TransactionDetail(
            @Schema(description = "거래 ID", example = "1") Long id,
            @Schema(description = "거래 유형 (EXPENSE / INCOME)", example = "EXPENSE") String type,
            @Schema(description = "거래 금액 (원)", example = "15000") long amount,
            @Schema(description = "거래 내역", example = "점심 식사") String title,
            @Schema(description = "카테고리 ID", example = "1") int categoryId,
            @Schema(description = "메모", example = "회사 근처 식당") String memo,
            @Schema(description = "감정 태그", example = "만족") String emotion,
            @Schema(description = "고정지출 여부", example = "false") boolean isRecurring,
            @Schema(description = "계획지출 여부", example = "true") boolean isPlanned,
            @Schema(description = "거래 시각", example = "12:30:00") LocalTime time) {}
}
