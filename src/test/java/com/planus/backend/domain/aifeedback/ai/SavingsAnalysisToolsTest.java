package com.planus.backend.domain.aifeedback.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class SavingsAnalysisToolsTest {

    /** 기준일: 11월 14일 → 이번 달 = 11/1~11/14 (14일), 평소 = 8/1~10/31 */
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 14);

    private ExpenseRepository expenseRepository;
    private SavingsAnalysisTools tools;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        tools = new SavingsAnalysisTools(expenseRepository);
    }

    private ToolContext toolContext() {
        return new ToolContext(Map.of("userId", 1L, SavingsAnalysisTools.AS_OF, AS_OF));
    }

    private Expense variableExpense(long amount, String emotion, LocalDateTime date) {
        return Expense.builder()
                .userId(1L)
                .categoryId(7)
                .type("EXPENSE")
                .amount(amount)
                .emotion(emotion)
                .expenseDate(date)
                .recurring(false)
                .planned(false)
                .build();
    }

    private void stub(List<Expense> expenses) {
        when(expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(eq(1L), eq(7), any(), any()))
                .thenReturn(expenses);
    }

    @Nested
    @DisplayName("getCategoryFrequency()")
    class GetCategoryFrequency {

        @Test
        @DisplayName("이번 달(1일~기준일)과 평소(직전 3개월)를 나눠 빈도·건당 평균·합계를 계산한다")
        void splitsThisMonthAndUsual() {
            stub(List.of(
                    variableExpense(30_000, null, LocalDateTime.of(2026, 8, 5, 12, 0)),
                    variableExpense(30_000, null, LocalDateTime.of(2026, 9, 5, 12, 0)),
                    variableExpense(30_000, null, LocalDateTime.of(2026, 10, 5, 12, 0)),
                    variableExpense(10_000, "SATISFIED", LocalDateTime.of(2026, 11, 1, 12, 0)),
                    variableExpense(20_000, "REGRET", LocalDateTime.of(2026, 11, 3, 18, 0)),
                    variableExpense(30_000, null, LocalDateTime.of(2026, 11, 5, 20, 0))));

            SavingsAnalysisTools.FrequencyStats result = tools.getCategoryFrequency(7, toolContext());

            SavingsAnalysisTools.PeriodStats thisMonth = result.thisMonth();
            assertThat(thisMonth.from()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(thisMonth.to()).isEqualTo(AS_OF);
            assertThat(thisMonth.days()).isEqualTo(14);
            assertThat(thisMonth.count()).isEqualTo(3);
            assertThat(thisMonth.weeklyAvgCount()).isEqualTo(1.5); // 3건 ÷ 14일 × 7
            assertThat(thisMonth.totalAmount()).isEqualTo(60_000);
            assertThat(thisMonth.avgAmountPerTransaction()).isEqualTo(20_000);

            SavingsAnalysisTools.PeriodStats usual = result.usual();
            assertThat(usual.from()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(usual.to()).isEqualTo(LocalDate.of(2026, 10, 31));
            assertThat(usual.count()).isEqualTo(3);
            assertThat(usual.monthlyAvgAmount()).isEqualTo(30_000);
        }

        @Test
        @DisplayName("거래가 없으면 0을 반환한다")
        void emptyExpenses() {
            stub(List.of());

            SavingsAnalysisTools.FrequencyStats result = tools.getCategoryFrequency(7, toolContext());

            assertThat(result.thisMonth().weeklyAvgCount()).isZero();
            assertThat(result.thisMonth().avgAmountPerTransaction()).isZero();
            assertThat(result.usual().count()).isZero();
        }

        @Test
        @DisplayName("반복·예정 지출은 제외한다")
        void excludesRecurring() {
            Expense recurring = Expense.builder()
                    .userId(1L)
                    .categoryId(7)
                    .type("EXPENSE")
                    .amount(50000)
                    .expenseDate(LocalDateTime.of(2026, 11, 1, 12, 0))
                    .recurring(true)
                    .planned(false)
                    .build();
            stub(List.of(recurring, variableExpense(10000, null, LocalDateTime.of(2026, 11, 2, 12, 0))));

            SavingsAnalysisTools.FrequencyStats result = tools.getCategoryFrequency(7, toolContext());

            assertThat(result.thisMonth().count()).isEqualTo(1);
            assertThat(result.thisMonth().avgAmountPerTransaction()).isEqualTo(10000);
        }

        @Test
        @DisplayName("기준일 기준으로 조회한다 — 이번 달 1일 ~ 기준일, 평소는 그 직전 3개월")
        void queriesFromUsualStartToAsOf() {
            stub(List.of());

            tools.getCategoryFrequency(7, toolContext());

            verify(expenseRepository)
                    .findByUserIdAndCategoryIdAndExpenseDateBetween(
                            eq(1L), eq(7), eq(LocalDate.of(2026, 8, 1).atStartOfDay()), argThat(to -> to.toLocalDate()
                                    .equals(AS_OF)));
        }
    }

    @Nested
    @DisplayName("getDayOfWeekDistribution()")
    class GetDayOfWeekDistribution {

        @Test
        @DisplayName("이번 달 요일별 금액과 비중을 반환한다")
        void calculatesDistribution() {
            // 2026-11-02 = Monday, 2026-11-06 = Friday
            stub(List.of(
                    variableExpense(10000, null, LocalDateTime.of(2026, 11, 2, 12, 0)),
                    variableExpense(20000, null, LocalDateTime.of(2026, 11, 6, 19, 0)),
                    variableExpense(15000, null, LocalDateTime.of(2026, 11, 6, 12, 0))));

            SavingsAnalysisTools.DayOfWeekStats result = tools.getDayOfWeekDistribution(7, toolContext());

            assertThat(result.from()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(result.totalAmount()).isEqualTo(45000);
            assertThat(result.byDay().get("MONDAY").amount()).isEqualTo(10000L);
            assertThat(result.byDay().get("FRIDAY").amount()).isEqualTo(35000L); // 20000 + 15000
            assertThat(result.byDay().get("FRIDAY").share()).isEqualTo(0.778); // 35000 ÷ 45000
            assertThat(result.byDay().get("TUESDAY").amount()).isZero();
        }

        @Test
        @DisplayName("이번 달 1일부터 조회한다 (이전 8주 고정 기간 아님)")
        void queriesThisMonthOnly() {
            stub(List.of());

            tools.getDayOfWeekDistribution(7, toolContext());

            verify(expenseRepository)
                    .findByUserIdAndCategoryIdAndExpenseDateBetween(
                            eq(1L), eq(7), eq(LocalDate.of(2026, 11, 1).atStartOfDay()), any());
        }
    }

    @Nested
    @DisplayName("getEmotionDistribution()")
    class GetEmotionDistribution {

        @Test
        @DisplayName("이번 달 감정별 건수·금액과 비중을 반환한다")
        void calculatesEmotionStats() {
            stub(List.of(
                    variableExpense(10000, "SATISFIED", LocalDateTime.of(2026, 11, 1, 12, 0)),
                    variableExpense(20000, "REGRET", LocalDateTime.of(2026, 11, 2, 12, 0)),
                    variableExpense(30000, "REGRET", LocalDateTime.of(2026, 11, 3, 12, 0)),
                    variableExpense(5000, null, LocalDateTime.of(2026, 11, 4, 12, 0))));

            SavingsAnalysisTools.EmotionStats result = tools.getEmotionDistribution(7, toolContext());

            assertThat(result.totalCount()).isEqualTo(4);
            assertThat(result.totalAmount()).isEqualTo(65000);

            SavingsAnalysisTools.EmotionStat satisfied = result.byEmotion().get("SATISFIED");
            assertThat(satisfied.count()).isEqualTo(1);
            assertThat(satisfied.totalAmount()).isEqualTo(10000);
            assertThat(satisfied.ratio()).isCloseTo(0.25, within(0.001));

            SavingsAnalysisTools.EmotionStat regret = result.byEmotion().get("REGRET");
            assertThat(regret.count()).isEqualTo(2);
            assertThat(regret.totalAmount()).isEqualTo(50000);
            assertThat(regret.amountShare()).isEqualTo(0.769); // 50000 ÷ 65000

            assertThat(result.byEmotion().get("UNTAGGED").count()).isEqualTo(1);
        }

        @Test
        @DisplayName("거래가 없으면 빈 분포")
        void emptyExpenses() {
            stub(List.of());

            SavingsAnalysisTools.EmotionStats result = tools.getEmotionDistribution(7, toolContext());

            assertThat(result.byEmotion()).isEmpty();
            assertThat(result.totalCount()).isZero();
        }
    }
}
