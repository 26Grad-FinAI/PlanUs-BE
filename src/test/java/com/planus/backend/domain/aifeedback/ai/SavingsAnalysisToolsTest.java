package com.planus.backend.domain.aifeedback.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SavingsAnalysisToolsTest {

    private ExpenseRepository expenseRepository;
    private SavingsAnalysisTools tools;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        tools = new SavingsAnalysisTools(expenseRepository);
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

    @Nested
    @DisplayName("getCategoryFrequency()")
    class GetCategoryFrequency {

        @Test
        @DisplayName("정상적으로 주간 빈도와 건당 평균 금액을 계산한다")
        void calculatesCorrectly() {
            List<Expense> expenses = List.of(
                    variableExpense(10000, "SATISFIED", LocalDateTime.of(2026, 11, 1, 12, 0)),
                    variableExpense(20000, "REGRET", LocalDateTime.of(2026, 11, 3, 18, 0)),
                    variableExpense(30000, null, LocalDateTime.of(2026, 11, 5, 20, 0)));

            when(expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(eq(1L), eq(7), any(), any()))
                    .thenReturn(expenses);

            SavingsAnalysisTools.FrequencyStats result = tools.getCategoryFrequency(1L, 7, 4);

            assertThat(result.weeklyAvgCount()).isEqualTo(3.0 / 4); // 3건 / 4주
            assertThat(result.avgAmountPerTransaction()).isEqualTo(20000); // (10000+20000+30000) / 3
        }

        @Test
        @DisplayName("거래가 없으면 0을 반환한다")
        void emptyExpenses() {
            when(expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(eq(1L), eq(7), any(), any()))
                    .thenReturn(List.of());

            SavingsAnalysisTools.FrequencyStats result = tools.getCategoryFrequency(1L, 7, 4);

            assertThat(result.weeklyAvgCount()).isEqualTo(0);
            assertThat(result.avgAmountPerTransaction()).isEqualTo(0);
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
            Expense variable = variableExpense(10000, null, LocalDateTime.of(2026, 11, 2, 12, 0));

            when(expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(eq(1L), eq(7), any(), any()))
                    .thenReturn(List.of(recurring, variable));

            SavingsAnalysisTools.FrequencyStats result = tools.getCategoryFrequency(1L, 7, 4);

            assertThat(result.weeklyAvgCount()).isEqualTo(1.0 / 4); // 변동 1건만
            assertThat(result.avgAmountPerTransaction()).isEqualTo(10000);
        }
    }

    @Nested
    @DisplayName("getDayOfWeekDistribution()")
    class GetDayOfWeekDistribution {

        @Test
        @DisplayName("요일별 지출 금액 분포를 반환한다")
        void calculatesDistribution() {
            // 2026-11-02 = Monday, 2026-11-06 = Friday
            List<Expense> expenses = List.of(
                    variableExpense(10000, null, LocalDateTime.of(2026, 11, 2, 12, 0)),
                    variableExpense(20000, null, LocalDateTime.of(2026, 11, 6, 19, 0)),
                    variableExpense(15000, null, LocalDateTime.of(2026, 11, 6, 12, 0)));

            when(expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(eq(1L), eq(7), any(), any()))
                    .thenReturn(expenses);

            Map<String, Long> result = tools.getDayOfWeekDistribution(1L, 7);

            assertThat(result).containsEntry("MONDAY", 10000L);
            assertThat(result).containsEntry("FRIDAY", 35000L); // 20000 + 15000
            assertThat(result).containsEntry("TUESDAY", 0L);
        }
    }

    @Nested
    @DisplayName("getEmotionDistribution()")
    class GetEmotionDistribution {

        @Test
        @DisplayName("감정별 통계를 반환한다")
        void calculatesEmotionStats() {
            List<Expense> expenses = List.of(
                    variableExpense(10000, "SATISFIED", LocalDateTime.of(2026, 11, 1, 12, 0)),
                    variableExpense(20000, "REGRET", LocalDateTime.of(2026, 11, 2, 12, 0)),
                    variableExpense(30000, "REGRET", LocalDateTime.of(2026, 11, 3, 12, 0)),
                    variableExpense(5000, null, LocalDateTime.of(2026, 11, 4, 12, 0)));

            when(expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(eq(1L), eq(7), any(), any()))
                    .thenReturn(expenses);

            Map<String, SavingsAnalysisTools.EmotionStat> result = tools.getEmotionDistribution(1L, 7);

            assertThat(result).containsKey("SATISFIED");
            assertThat(result.get("SATISFIED").count()).isEqualTo(1);
            assertThat(result.get("SATISFIED").totalAmount()).isEqualTo(10000);
            assertThat(result.get("SATISFIED").ratio()).isCloseTo(0.25, within(0.01));

            assertThat(result).containsKey("REGRET");
            assertThat(result.get("REGRET").count()).isEqualTo(2);
            assertThat(result.get("REGRET").totalAmount()).isEqualTo(50000);

            assertThat(result).containsKey("UNTAGGED");
            assertThat(result.get("UNTAGGED").count()).isEqualTo(1);
        }

        @Test
        @DisplayName("거래가 없으면 빈 맵 반환")
        void emptyExpenses() {
            when(expenseRepository.findByUserIdAndCategoryIdAndExpenseDateBetween(eq(1L), eq(7), any(), any()))
                    .thenReturn(List.of());

            Map<String, SavingsAnalysisTools.EmotionStat> result = tools.getEmotionDistribution(1L, 7);

            assertThat(result).isEmpty();
        }
    }
}
