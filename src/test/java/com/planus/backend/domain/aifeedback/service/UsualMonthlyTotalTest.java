package com.planus.backend.domain.aifeedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.entity.Expense;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 예산이 배분되지 않은 카테고리(기타)의 '평소' — 직전 3개월 중 지출이 있는 달의 월평균 (#141). */
class UsualMonthlyTotalTest {

    private static final LocalDate MONTH_START = LocalDate.of(2026, 9, 1);
    private static final int ETC = 10;

    private static Expense expense(int category, LocalDate date, long amount) {
        return Expense.builder()
                .userId(1L)
                .categoryId(category)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(date.atTime(12, 0))
                .recurring(false)
                .planned(false)
                .build();
    }

    @Test
    @DisplayName("직전 3개월 월평균 — 지출이 있는 달만, 다른 카테고리와 이번 달은 제외")
    void averagesPriorMonthsWithSpending() {
        List<Expense> window = new ArrayList<>();
        window.add(expense(ETC, LocalDate.of(2026, 8, 10), 300_000));
        window.add(expense(ETC, LocalDate.of(2026, 8, 20), 100_000)); // 8월 400,000
        window.add(expense(ETC, LocalDate.of(2026, 7, 5), 600_000)); // 7월 600,000
        window.add(expense(ETC, LocalDate.of(2026, 5, 31), 900_000)); // 3개월 밖
        window.add(expense(1, LocalDate.of(2026, 8, 15), 700_000)); // 다른 카테고리
        window.add(expense(ETC, LocalDate.of(2026, 9, 2), 800_000)); // 이번 달

        assertThat(AiFeedbackService.usualMonthlyTotal(window, MONTH_START, ETC))
                .isEqualTo(500_000);
    }

    @Test
    @DisplayName("지출이 있는 달이 2개월 미만이면 평소를 알 수 없다 — 0")
    void unknownWhenHistoryTooShort() {
        List<Expense> window = List.of(expense(ETC, LocalDate.of(2026, 8, 10), 400_000));

        assertThat(AiFeedbackService.usualMonthlyTotal(window, MONTH_START, ETC))
                .isZero();
        assertThat(AiFeedbackService.usualMonthlyTotal(List.of(), MONTH_START, ETC))
                .isZero();
    }

    @Test
    @DisplayName("월 경계 — 직전 달 1일은 포함, 이번 달 1일은 제외")
    void monthBoundaries() {
        List<Expense> window = List.of(
                expense(ETC, LocalDate.of(2026, 8, 1), 100_000),
                expense(ETC, LocalDate.of(2026, 7, 31), 300_000),
                expense(ETC, LocalDate.of(2026, 9, 1), 999_999));

        assertThat(AiFeedbackService.usualMonthlyTotal(window, MONTH_START, ETC))
                .isEqualTo(200_000);
    }

    @Test
    @DisplayName("이상치 기준 예산 — 배정된 예산이 있으면 그대로, 0원(기타)이면 평소 월 지출, 평소도 모르면 0")
    void anomalyBudgetFallsBackToUsual() {
        List<Expense> window = List.of(
                expense(ETC, LocalDate.of(2026, 8, 10), 400_000), expense(ETC, LocalDate.of(2026, 7, 10), 600_000));
        LocalDate weekEnd = LocalDate.of(2026, 9, 13);

        assertThat(AiFeedbackService.anomalyBudget(java.util.Map.of(ETC, 0L, 1, 300_000L), window, weekEnd, 1))
                .isEqualTo(300_000);
        assertThat(AiFeedbackService.anomalyBudget(java.util.Map.of(ETC, 0L), window, weekEnd, ETC))
                .isEqualTo(500_000);
        assertThat(AiFeedbackService.anomalyBudget(java.util.Map.of(ETC, 0L), List.of(), weekEnd, ETC))
                .isZero();
    }
}
