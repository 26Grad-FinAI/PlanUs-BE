package com.planus.backend.domain.home.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "홈 화면 최근 거래 내역 응답")
public record HomeRecentResponse(@Schema(description = "날짜별 거래 그룹 목록") List<DailyGroup> dailyGroups) {

    @Schema(description = "날짜별 거래 그룹")
    public record DailyGroup(
            @Schema(description = "거래 날짜", example = "2026-10-27") LocalDate date,
            @Schema(description = "해당 날짜의 거래 목록") List<RecentTransaction> transactions) {}

    @Schema(description = "최근 거래 항목")
    public record RecentTransaction(
            @Schema(description = "거래 내역", example = "점심 식사") String title,
            @Schema(description = "카테고리명", example = "식대") String categoryName,
            @Schema(description = "부호 포함 금액 (지출 음수, 수입 양수)", example = "-6500") long amount) {}
}
