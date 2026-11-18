package com.planus.backend.domain.aifeedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.entity.Expense;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 피드백의 '평소' = 이번 주를 뺀 직전 4주 주당 평균 변동지출 (#144). */
class RecentWeeklyAverageTest {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 10, 5); // 월요일

    @Test
    @DisplayName("이번 주를 빼고 직전 4주 합계를 4로 나눈다 — 지출이 없던 주도 0원으로 넣는다")
    void averagesPreviousFourWeeks() {
        List<Expense> window = List.of(
                variable(WEEK_START.minusDays(1), 100_000), // 직전 1주
                variable(WEEK_START.minusWeeks(1).minusDays(1), 200_000), // 직전 2주
                variable(WEEK_START.minusWeeks(3), 100_000), // 직전 4주 첫날
                variable(WEEK_START.minusWeeks(4).minusDays(1), 900_000), // 5주 전 — 제외
                variable(WEEK_START, 50_000)); // 이번 주 — 제외

        assertThat(AiFeedbackService.recentWeeklyAverage(window, WEEK_START, 4, 3))
                .isEqualTo(100_000); // 400,000 / 4
    }

    @Test
    @DisplayName("지출이 있는 주가 3주 미만이면 0 — 기록이 짧은 사용자에게 낮은 평균을 '평소'로 보여 주지 않는다")
    void zeroWhenTooFewActiveWeeks() {
        List<Expense> window =
                List.of(variable(WEEK_START.minusDays(1), 100_000), variable(WEEK_START.minusDays(8), 100_000));

        assertThat(AiFeedbackService.recentWeeklyAverage(window, WEEK_START, 4, 3))
                .isZero();
    }

    @Test
    @DisplayName("고정·예정 지출은 넣지 않는다")
    void variableOnly() {
        List<Expense> window = List.of(
                variable(WEEK_START.minusDays(1), 40_000),
                variable(WEEK_START.minusDays(8), 40_000),
                variable(WEEK_START.minusDays(15), 40_000),
                Expense.builder()
                        .categoryId(9)
                        .type("EXPENSE")
                        .amount(1_000_000L)
                        .expenseDate(WEEK_START.minusDays(2).atStartOfDay())
                        .recurring(true)
                        .planned(false)
                        .build());

        assertThat(AiFeedbackService.recentWeeklyAverage(window, WEEK_START, 4, 3))
                .isEqualTo(30_000);
    }

    private static Expense variable(LocalDate date, long amount) {
        return Expense.builder()
                .categoryId(10)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(date.atStartOfDay())
                .recurring(false)
                .planned(false)
                .build();
    }
}
