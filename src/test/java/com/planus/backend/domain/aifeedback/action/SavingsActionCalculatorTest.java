package com.planus.backend.domain.aifeedback.action;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.cause.CauseSignals;
import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.entity.RejectionReason;
import com.planus.backend.domain.aifeedback.entity.UserReaction;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SavingsActionCalculatorTest {

    private final AiFeedbackProperties p = new AiFeedbackProperties();
    private final SavingsActionCalculator calculator = new SavingsActionCalculator(p);

    // ── 필터링 ──

    @Nested
    @DisplayName("필터링")
    class FilterTest {

        @Test
        @DisplayName("essential 카테고리는 제외")
        void excludes_essential() {
            // categoryId 5 = essential (기본값)
            Map<Integer, Long> overspend = Map.of(5, 100_000L);
            List<ActionPlan> plans = calculator.calculate(
                    overspend, 100_000, 15, 15, Map.of(5, 200_000L), Map.of(), List.of(), List.of(), List.of());
            assertThat(plans).isEmpty();
        }

        @Test
        @DisplayName("sensitiveAreas 카테고리는 제외")
        void excludes_sensitive() {
            Map<Integer, Long> overspend = Map.of(2, 100_000L);
            List<ActionPlan> plans = calculator.calculate(
                    overspend, 100_000, 15, 15, Map.of(2, 200_000L), Map.of(), List.of(), List.of(), List.of(2L));
            assertThat(plans).isEmpty();
        }

        @Test
        @DisplayName("SATISFIED 비중 70%+ 카테고리 제외, 대안 없으면 유지")
        void excludes_satisfied_with_fallback() {
            // 카테고리 2만 있고, SATISFIED 100%
            Map<Integer, Long> overspend = Map.of(2, 100_000L);
            List<Expense> expenses =
                    List.of(variableWithEmotion(2, 50_000, "SATISFIED"), variableWithEmotion(2, 50_000, "SATISFIED"));

            // 대안 없음 → 폴백으로 유지
            List<ActionPlan> plans = calculator.calculate(
                    overspend, 100_000, 15, 15, Map.of(2, 200_000L), Map.of(), expenses, List.of(), List.of());
            assertThat(plans).isNotEmpty();
        }
    }

    // ── 점수 기반 배분 ──

    @Nested
    @DisplayName("점수 기반 배분")
    class ScoreTest {

        @Test
        @DisplayName("REGRET 카테고리가 먼저 배분됨")
        void regret_category_prioritized() {
            Map<Integer, Long> overspend = Map.of(2, 80_000L, 3, 80_000L);
            List<Expense> expenses = List.of(
                    variableWithEmotion(2, 40_000, "REGRET"),
                    variableWithEmotion(2, 40_000, "REGRET"),
                    variableWithEmotion(3, 40_000, "SATISFIED"),
                    variableWithEmotion(3, 40_000, "SATISFIED"));

            List<ActionPlan> plans = calculator.calculate(
                    overspend,
                    50_000,
                    15,
                    15,
                    Map.of(2, 200_000L, 3, 200_000L),
                    Map.of(),
                    expenses,
                    List.of(),
                    List.of());

            assertThat(plans).isNotEmpty();
            assertThat(plans.get(0).categoryId()).isEqualTo(2); // REGRET → 높은 점수
        }

        @Test
        @DisplayName("TOO_REPETITIVE 거부 이력은 점수 감산")
        void too_repetitive_lowers_score() {
            Map<Integer, Long> overspend = Map.of(2, 80_000L, 4, 80_000L);
            List<UserReaction> rejections = List.of(rejection(2, RejectionReason.TOO_REPETITIVE));

            List<ActionPlan> plans = calculator.calculate(
                    overspend,
                    50_000,
                    15,
                    15,
                    Map.of(2, 200_000L, 4, 200_000L),
                    Map.of(),
                    List.of(),
                    rejections,
                    List.of());

            assertThat(plans).isNotEmpty();
            // 카테고리 4가 먼저 (2는 TOO_REPETITIVE로 감산)
            assertThat(plans.get(0).categoryId()).isEqualTo(4);
        }
    }

    // ── 절감액 계산 ──

    @Nested
    @DisplayName("절감액 계산")
    class ReductionTest {

        @Test
        @DisplayName("maxReducible은 남은 기간 예상 지출 × reductionRatio")
        void max_reducible_based_on_remaining_expected() {
            // 경과 15일, 잔여 15일, 카테고리 MTD = 150,000
            // 일평균 = 10,000, 남은 예상 = 150,000, maxReducible = 75,000 (50%)
            // 초과분 = 200,000, gap = 200,000 → min(200k, 200k, 75k) = 75,000
            Map<Integer, Long> overspend = Map.of(2, 200_000L);
            List<ActionPlan> plans = calculator.calculate(
                    overspend, 200_000, 15, 15, Map.of(2, 150_000L), Map.of(), List.of(), List.of(), List.of());

            assertThat(plans).hasSize(1);
            assertThat(plans.get(0).reductionWon()).isEqualTo(75_000);
        }

        @Test
        @DisplayName("절감액은 저축 갭을 초과하지 않음")
        void reduction_capped_by_savings_gap() {
            Map<Integer, Long> overspend = Map.of(2, 200_000L);
            List<ActionPlan> plans = calculator.calculate(
                    overspend, 30_000, 15, 15, Map.of(2, 150_000L), Map.of(), List.of(), List.of(), List.of());

            assertThat(plans).hasSize(1);
            assertThat(plans.get(0).reductionWon()).isEqualTo(30_000);
        }
    }

    // ── dominantFactor 전달 ──

    @Nested
    @DisplayName("dominantFactor 전달")
    class DominantFactorTest {

        @Test
        @DisplayName("CauseSignals의 dominantFactor가 ActionPlan에 전달됨")
        void passes_dominant_factor() {
            Map<Integer, Long> overspend = Map.of(2, 100_000L);
            CauseSignals signals = new CauseSignals(2.5, "FREQUENCY", 0.8, 0.2, Map.of(), null, List.of(), false);
            Map<Integer, CauseSignals> causeMap = Map.of(2, signals);

            List<ActionPlan> plans = calculator.calculate(
                    overspend, 100_000, 15, 15, Map.of(2, 200_000L), causeMap, List.of(), List.of(), List.of());

            assertThat(plans).hasSize(1);
            assertThat(plans.get(0).dominantFactor()).isEqualTo("FREQUENCY");
        }

        @Test
        @DisplayName("CauseSignals가 없으면 dominantFactor는 null")
        void null_when_no_cause_signals() {
            Map<Integer, Long> overspend = Map.of(2, 100_000L);

            List<ActionPlan> plans = calculator.calculate(
                    overspend, 100_000, 15, 15, Map.of(2, 200_000L), Map.of(), List.of(), List.of(), List.of());

            assertThat(plans).hasSize(1);
            assertThat(plans.get(0).dominantFactor()).isNull();
        }
    }

    // ── 가드 ──

    @Nested
    @DisplayName("가드")
    class GuardTest {

        @Test
        @DisplayName("savingsGap ≤ 0이면 빈 리스트")
        void empty_when_no_gap() {
            List<ActionPlan> plans = calculator.calculate(
                    Map.of(2, 100_000L), 0, 15, 15, Map.of(2, 200_000L), Map.of(), List.of(), List.of(), List.of());
            assertThat(plans).isEmpty();
        }

        @Test
        @DisplayName("월초(경과 3일 미만)에는 액션 미생성")
        void empty_when_early_month() {
            List<ActionPlan> plans = calculator.calculate(
                    Map.of(2, 100_000L),
                    100_000,
                    2,
                    28,
                    Map.of(2, 200_000L),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of());
            assertThat(plans).isEmpty();
        }

        @Test
        @DisplayName("경과 3일이면 정상 동작")
        void works_at_day_3() {
            List<ActionPlan> plans = calculator.calculate(
                    Map.of(2, 100_000L), 100_000, 3, 27, Map.of(2, 60_000L), Map.of(), List.of(), List.of(), List.of());
            assertThat(plans).isNotEmpty();
        }
    }

    // ── 헬퍼 ──

    private static Expense variableWithEmotion(int categoryId, long amount, String emotion) {
        return Expense.builder()
                .categoryId(categoryId)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(LocalDate.of(2026, 7, 10).atStartOfDay())
                .emotion(emotion)
                .recurring(false)
                .planned(false)
                .build();
    }

    private static UserReaction rejection(int categoryId, RejectionReason reason) {
        return UserReaction.builder()
                .categoryId(categoryId)
                .rejectionReason(reason)
                .build();
    }
}
