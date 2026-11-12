package com.planus.backend.domain.home.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Schema(description = "홈 화면 월별 최근 거래 내역 응답")
public record HomeRecentResponse(
        @Schema(description = "거래 목록") List<RecentTransaction> transactions,
        @Schema(description = "다음 페이지 커서 (마지막 페이지면 null)", nullable = true) String nextCursor) {

    @Schema(description = "최근 거래 항목")
    public record RecentTransaction(
            @Schema(description = "거래 ID", example = "1") Long id,
            @Schema(description = "거래 유형 (EXPENSE / INCOME)", example = "EXPENSE") String type,
            @Schema(description = "부호 포함 금액 (지출 음수, 수입 양수)", example = "-6500") long amount,
            @Schema(description = "거래 내역", example = "점심 식사") String title,
            @Schema(description = "카테고리 ID", example = "1") int categoryId,
            @Schema(description = "거래 날짜", example = "2026-10-27") LocalDate date,
            @Schema(description = "거래 시각", example = "12:30:00") LocalTime time) {}
}
