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
        @DisplayName("줄일 금액이 5천 원 미만이거나 저축 갭의 5% 미만인 액션은 만들지 않고 갭을 다음 카테고리에 남긴다 (#143)")
        void skips_trivial_actions() {
            // 갭 200,000 → 하한 max(5,000, 10,000) = 10,000. 카테고리 4는 초과 4,000원뿐, 5는 8,000원, 7은 충분
            Map<Integer, Long> overspend = Map.of(4, 4_000L, 5, 8_000L, 7, 300_000L);
            List<ActionPlan> plans = calculator.calculate(
                    overspend,
                    200_000,
                    15,
                    15,
                    Map.of(4, 100_000L, 5, 100_000L, 7, 600_000L),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of());

            assertThat(plans).extracting(ActionPlan::categoryId).containsExactly(7);
            assertThat(plans.getFirst().reductionWon()).isEqualTo(200_000);
        }

        @Test
        @DisplayName("하한 설정값 검증 — 음수·1 이상 비율은 시작 시 거부 (#143)")
        void rejects_invalid_threshold() {
            AiFeedbackProperties props = new AiFeedbackProperties();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> props.setMinActionWon(-1))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> props.setMinActionGapRatio(1.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("essential 카테고리는 제외")
        void excludes_essential() {
            // categoryId 3 = essential (보건, 기본값)
            Map<Integer, Long> overspend = Map.of(3, 100_000L);
            List<ActionPlan> plans = calculator.calculate(
                    overspend, 100_000, 15, 15, Map.of(3, 200_000L), Map.of(), List.of(), List.of(), List.of());
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
        @DisplayName("만족 태그가 많아도 초과면 절약 대상 — 만족 비중으로 제외하지 않는다 (#141)")
        void satisfied_category_still_included() {
            // 카테고리 7(식대)은 만족 100%, 카테고리 4는 태그 없음. 갭 150,000 = 4에서 100,000 + 7에서 50,000
            Map<Integer, Long> overspend = Map.of(7, 100_000L, 4, 100_000L);
            List<Expense> expenses =
                    List.of(variableWithEmotion(7, 50_000, "SATISFIED"), variableWithEmotion(7, 50_000, "SATISFIED"));

            List<ActionPlan> plans = calculator.calculate(
                    overspend,
                    150_000,
                    15,
                    15,
                    Map.of(7, 400_000L, 4, 400_000L),
                    Map.of(),
                    expenses,
                    List.of(),
                    List.of());

            assertThat(plans).extracting(ActionPlan::categoryId).containsExactlyInAnyOrder(7, 4);
            assertThat(plans.get(0).categoryId()).isEqualTo(4); // 만족은 순위만 뒤로
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
        @DisplayName("감정 점수는 건수가 아니라 금액 비중 — 소액 만족 9건보다 후회 큰 한 건이 우선 (#141)")
        void emotion_weighted_by_amount() {
            Map<Integer, Long> overspend = Map.of(7, 80_000L, 4, 80_000L);
            List<Expense> expenses = new java.util.ArrayList<>();
            expenses.add(variableWithEmotion(7, 300_000, "REGRET"));
            for (int i = 0; i < 9; i++) expenses.add(variableWithEmotion(7, 5_000, "SATISFIED"));
            expenses.add(variableWithEmotion(4, 100_000, "SATISFIED"));
            expenses.add(variableWithEmotion(4, 100_000, "SATISFIED"));

            List<ActionPlan> plans = calculator.calculate(
                    overspend,
                    80_000,
                    15,
                    15,
                    Map.of(7, 400_000L, 4, 400_000L),
                    Map.of(),
                    expenses,
                    List.of(),
                    List.of());

            assertThat(plans).extracting(ActionPlan::categoryId).containsExactly(7);
        }

        @Test
        @DisplayName("태그가 붙은 금액이 변동 지출의 절반 미만이면 감정 점수를 쓰지 않는다 (#141)")
        void weak_tag_coverage_ignored() {
            Map<Integer, Long> overspend = Map.of(7, 100_000L);
            // 후회 10,000원만 태그, 나머지 190,000원은 태그 없음 → 태그 5%
            List<Expense> weak = List.of(variableWithEmotion(7, 10_000, "REGRET"), variable(7, 190_000));
            List<Expense> enough = List.of(variableWithEmotion(7, 150_000, "REGRET"), variable(7, 50_000));

            double weakScore = calculator
                    .calculate(overspend, 100_000, 15, 15, Map.of(7, 400_000L), Map.of(), weak, List.of(), List.of())
                    .get(0)
                    .feasibilityScore();
            double enoughScore = calculator
                    .calculate(overspend, 100_000, 15, 15, Map.of(7, 400_000L), Map.of(), enough, List.of(), List.of())
                    .get(0)
                    .feasibilityScore();

            assertThat(weakScore).isEqualTo(1.0);
            assertThat(enoughScore).isGreaterThan(weakScore);
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
            // 남은 기간 예상 지출 = 150,000 → maxReducible = 75,000 (50%)
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
            CauseSignals signals =
                    new CauseSignals(2.5, "FREQUENCY", 0.8, 0.2, Map.of(), Map.of(), null, List.of(), false);
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

    private static Expense variable(int categoryId, long amount) {
        return variableWithEmotion(categoryId, amount, null);
    }

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
