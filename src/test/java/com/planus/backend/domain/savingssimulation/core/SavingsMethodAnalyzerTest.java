package com.planus.backend.domain.savingssimulation.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.savingssimulation.core.SavingsMethodAnalyzer.Input;
import com.planus.backend.domain.savingssimulation.core.SavingsMethodAnalyzer.Level;
import com.planus.backend.domain.savingssimulation.core.SavingsMethodAnalyzer.Plan;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SavingsMethodAnalyzerTest {

    /** 2026-11-10 기준: 11월은 30일, 남은 20일. */
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 10);

    private static Expense e(LocalDate date, long amount, String emotion) {
        return Expense.builder()
                .categoryId(1)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(date.atStartOfDay())
                .emotion(emotion)
                .recurring(false)
                .planned(false)
                .build();
    }

    private static List<Expense> thisMonth(int count, long amount, String emotion) {
        List<Expense> l = new ArrayList<>();
        for (int i = 0; i < count; i++) l.add(e(LocalDate.of(2026, 11, 1 + i % 10), amount, emotion));
        return l;
    }

    /** 지난 달 한 건짜리 합계. */
    private static Expense pastMonth(int monthsAgo, long total) {
        return e(AS_OF.minusMonths(monthsAgo).withDayOfMonth(15), total, null);
    }

    private static Input input(String factor, long reduction, List<Expense> now, List<Expense> past, long remaining) {
        return new Input(1, factor, reduction, AS_OF, now, past, remaining, false);
    }

    @Test
    @DisplayName("빈도 방식: 10일간 5번(건당 2만 원)이면 남은 20일 예상 10번, 감축 6만 원은 3번 덜 쓰기")
    void method_frequency() {
        Plan p = SavingsMethodAnalyzer.analyze(
                input("FREQUENCY", 60_000, thisMonth(5, 20_000, null), List.of(), 200_000));

        assertThat(p.remainingDays()).isEqualTo(20);
        assertThat(p.expectedCount()).isEqualTo(10);
        assertThat(p.cutCount()).isEqualTo(3);
        assertThat(p.method()).isEqualTo("남은 20일간 3번 덜 쓰기 (예상 10번 → 7번)");
    }

    @Test
    @DisplayName("단가 방식: 건당 금액을 줄이는 방법으로 말한다")
    void method_unitPrice() {
        Plan p = SavingsMethodAnalyzer.analyze(
                input("UNIT_PRICE", 50_000, thisMonth(5, 20_000, null), List.of(), 200_000));

        assertThat(p.cutCount()).isZero();
        assertThat(p.method()).isEqualTo("남은 20일간 한 번에 20,000원꼴 → 15,000원꼴로 (예상 10번)");
    }

    @Test
    @DisplayName("감축액을 채우도록 덜 쓸 횟수는 올림한다 — 49,000원 ÷ 건당 20,000원은 3번")
    void method_cutRoundedUp() {
        Plan p = SavingsMethodAnalyzer.analyze(
                input("FREQUENCY", 49_000, thisMonth(5, 20_000, null), List.of(), 200_000));

        assertThat(p.cutCount()).isEqualTo(3);
        assertThat(p.cutCount() * 20_000).isGreaterThanOrEqualTo(49_000);
    }

    @Test
    @DisplayName("덜 쓸 횟수는 예상 횟수를 넘지 않는다")
    void method_cutCappedAtExpected() {
        Plan p = SavingsMethodAnalyzer.analyze(
                input("FREQUENCY", 900_000, thisMonth(5, 20_000, null), List.of(), 1_000_000));

        assertThat(p.cutCount()).isEqualTo(p.expectedCount());
    }

    @Test
    @DisplayName("이번 달 거래가 없으면 직전 3개월 기록으로 횟수를 추정하고, 기록도 없으면 방법을 만들지 않는다")
    void method_noThisMonth() {
        List<Expense> past = List.of(pastMonth(1, 90_000), pastMonth(2, 90_000), pastMonth(3, 90_000));
        assertThat(SavingsMethodAnalyzer.analyze(input("FREQUENCY", 30_000, List.of(), past, 100_000))
                        .method())
                .isNotNull();
        assertThat(SavingsMethodAnalyzer.analyze(input("FREQUENCY", 30_000, List.of(), List.of(), 100_000))
                        .method())
                .isNull();
    }

    @Test
    @DisplayName("월 마지막 날에는 남은 기간이 없어 방법이 없다")
    void method_lastDay() {
        Input in = new Input(
                1, "FREQUENCY", 10_000, LocalDate.of(2026, 11, 30), thisMonth(5, 20_000, null), List.of(), 0, false);
        assertThat(SavingsMethodAnalyzer.analyze(in).method()).isNull();
    }

    @Test
    @DisplayName("작은 감축 + 과거에 달성한 달이 많으면 쉬움")
    void feasibility_easy() {
        List<Expense> past = List.of(pastMonth(1, 150_000), pastMonth(2, 160_000), pastMonth(3, 170_000));
        Plan p = SavingsMethodAnalyzer.analyze(input("FREQUENCY", 20_000, thisMonth(5, 20_000, null), past, 200_000));

        assertThat(p.level()).isEqualTo(Level.EASY);
        assertThat(p.reasons()).anyMatch(r -> r.contains("지난 3개월 중 3개월"));
    }

    @Test
    @DisplayName("큰 감축 + 과거에 달성한 적 없으면 어려움")
    void feasibility_hard() {
        List<Expense> past = List.of(pastMonth(1, 400_000), pastMonth(2, 420_000), pastMonth(3, 430_000));
        Plan p = SavingsMethodAnalyzer.analyze(input("FREQUENCY", 150_000, thisMonth(5, 20_000, null), past, 200_000));

        assertThat(p.level()).isEqualTo(Level.HARD);
    }

    @Test
    @DisplayName("만족 소비가 절반 이상이면 한 단계 어려워지고 근거에 나온다")
    void feasibility_satisfiedMakesHarder() {
        List<Expense> past = List.of(pastMonth(1, 400_000), pastMonth(2, 400_000), pastMonth(3, 400_000));
        Plan plain =
                SavingsMethodAnalyzer.analyze(input("FREQUENCY", 60_000, thisMonth(5, 20_000, null), past, 200_000));
        Plan satisfied = SavingsMethodAnalyzer.analyze(
                input("FREQUENCY", 60_000, thisMonth(5, 20_000, "SATISFIED"), past, 200_000));

        assertThat(satisfied.level().ordinal())
                .isGreaterThanOrEqualTo(plain.level().ordinal());
        assertThat(satisfied.reasons()).anyMatch(r -> r.contains("만족한 소비"));
        assertThat(plain.reasons()).noneMatch(r -> r.contains("만족한 소비"));
    }

    @Test
    @DisplayName("충동·후회 소비가 절반 이상이면 쉬운 쪽으로 기운다")
    void feasibility_regretMakesEasier() {
        List<Expense> past = List.of(pastMonth(1, 400_000), pastMonth(2, 400_000), pastMonth(3, 400_000));
        Plan plain =
                SavingsMethodAnalyzer.analyze(input("FREQUENCY", 60_000, thisMonth(5, 20_000, null), past, 200_000));
        Plan regret = SavingsMethodAnalyzer.analyze(
                input("FREQUENCY", 60_000, thisMonth(5, 20_000, "REGRET"), past, 200_000));

        assertThat(regret.level().ordinal()).isLessThanOrEqualTo(plain.level().ordinal());
        assertThat(regret.reasons()).anyMatch(r -> r.contains("충동·후회"));
    }

    @Test
    @DisplayName("거절 이력이 있으면 어려운 쪽으로 기울고 근거에 나온다")
    void feasibility_rejectedBefore() {
        Input base = input("FREQUENCY", 40_000, thisMonth(5, 20_000, null), List.of(), 200_000);
        Input rejected = new Input(
                base.categoryId(),
                base.dominantFactor(),
                base.reductionWon(),
                base.asOf(),
                base.thisMonth(),
                base.past(),
                base.remainingExpected(),
                true);

        assertThat(SavingsMethodAnalyzer.analyze(rejected).level().ordinal())
                .isGreaterThanOrEqualTo(
                        SavingsMethodAnalyzer.analyze(base).level().ordinal());
        assertThat(SavingsMethodAnalyzer.analyze(rejected).reasons()).anyMatch(r -> r.contains("줄이고 싶지 않다고"));
    }

    @Test
    @DisplayName("남은 기간 예상 지출이 없으면 어려움이고 근거를 말한다")
    void feasibility_noRemainingExpected() {
        Plan p = SavingsMethodAnalyzer.analyze(input("FREQUENCY", 30_000, thisMonth(5, 20_000, null), List.of(), 0));

        assertThat(p.reasons().get(0)).contains("예상되는 지출이 없어");
        assertThat(p.level()).isNotEqualTo(Level.EASY);
    }
}
