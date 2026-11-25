package com.planus.backend.domain.aifeedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.entity.Expense;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 월말 예상 초과의 큰 부분을 차지하는 이미 쓴 한 건 (#141). */
class LargestSingleOfOverspendTest {

    private static final LocalDate MONTH_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate WEEK_END = LocalDate.of(2026, 9, 20);

    private static Expense expense(int category, LocalDate date, long amount, String memo) {
        return Expense.builder()
                .userId(1L)
                .categoryId(category)
                .type("EXPENSE")
                .amount(amount)
                .memo(memo)
                .expenseDate(date.atTime(12, 0))
                .recurring(false)
                .planned(false)
                .build();
    }

    @Test
    @DisplayName("초과의 30% 이상이고 5만 원 이상인 가장 큰 한 건만 돌려준다")
    void returnsOnlyWhenLargeEnough() {
        List<Expense> window = List.of(
                expense(7, LocalDate.of(2026, 9, 5), 204_000, "리정원"),
                expense(7, LocalDate.of(2026, 9, 10), 30_000, "카페"));

        assertThat(AiFeedbackService.largestSingleOfOverspend(window, 7, MONTH_START, WEEK_END, 330_000))
                .extracting(Expense::getAmount)
                .isEqualTo(204_000L);
        // 초과가 너무 커서 한 건이 30% 미만
        assertThat(AiFeedbackService.largestSingleOfOverspend(window, 7, MONTH_START, WEEK_END, 1_000_000))
                .isNull();
    }

    @Test
    @DisplayName("5만 원 미만은 초과의 대부분이어도 따로 알리지 않고, 다른 카테고리·이번 달 밖 거래는 보지 않는다")
    void ignoresSmallOtherCategoryAndOtherMonth() {
        List<Expense> window = List.of(
                expense(7, LocalDate.of(2026, 9, 5), 40_000, null),
                expense(1, LocalDate.of(2026, 9, 6), 300_000, null),
                expense(7, LocalDate.of(2026, 8, 30), 300_000, null));

        assertThat(AiFeedbackService.largestSingleOfOverspend(window, 7, MONTH_START, WEEK_END, 50_000))
                .isNull();
    }
}
