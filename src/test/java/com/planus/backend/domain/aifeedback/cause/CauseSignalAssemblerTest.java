package com.planus.backend.domain.aifeedback.cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.entity.UserProfile;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CauseSignalAssemblerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CauseSignalAssembler assembler = new CauseSignalAssembler(mapper);

    private static final LocalDate WEEK_START = LocalDate.of(2026, 7, 6); // 월요일
    private static final LocalDate WEEK_END = LocalDate.of(2026, 7, 12); // 일요일
    private static final int CATEGORY = 1;

    // ── selfRatio ──

    @Nested
    @DisplayName("selfRatio — 본인 과거 대비 배율")
    class SelfRatioTest {

        @Test
        @DisplayName("과거 7주 주당 평균 대비 1.5배 이상이면 배율 반환")
        void returns_ratio_when_above_threshold() {
            // 과거 7주: 4주에 걸쳐 주당 30,000씩 (총 120,000, 주당 평균 = 120,000/7 ≈ 17,143)
            // 이번 주: 60,000 → 60,000/17,143 ≈ 3.5배
            List<Expense> expenses = new ArrayList<>();
            for (int w = 1; w <= 4; w++) {
                expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(w), 30_000));
            }
            expenses.add(variable(CATEGORY, WEEK_START, 60_000));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.selfRatio()).isNotNull();
            assertThat(result.selfRatio()).isGreaterThanOrEqualTo(1.5);
        }

        @Test
        @DisplayName("분모는 activeWeeks가 아닌 BASELINE_WEEKS(7) — 빈도 증가 감지")
        void denominator_is_baseline_weeks_not_active_weeks() {
            // 과거 7주 중 3주에만 지출: 총 165,000원
            // activeWeeks=3으로 나누면 55,000 → 이번 주 60,000은 1.09배 (신호 없음)
            // BASELINE_WEEKS=7로 나누면 23,571 → 이번 주 60,000은 2.5배 (신호 발생)
            List<Expense> expenses = new ArrayList<>();
            expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(1), 50_000));
            expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(3), 60_000));
            expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(5), 55_000));
            expenses.add(variable(CATEGORY, WEEK_START, 60_000));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.selfRatio()).isNotNull();
            assertThat(result.selfRatio()).isGreaterThan(2.0);
        }

        @Test
        @DisplayName("지출 있는 주가 3주 미만이면 null")
        void null_when_fewer_than_3_active_weeks() {
            List<Expense> expenses = new ArrayList<>();
            expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(1), 50_000));
            expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(2), 50_000));
            expenses.add(variable(CATEGORY, WEEK_START, 100_000));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.selfRatio()).isNull();
        }

        @Test
        @DisplayName("주당 평균이 10,000원 미만이면 null — 소액 카테고리 허위 배율 방지")
        void null_when_baseline_avg_below_minimum() {
            // 과거 7주: 4주에 걸쳐 주당 3,000씩 → 총 12,000 / 7 ≈ 1,714원
            List<Expense> expenses = new ArrayList<>();
            for (int w = 1; w <= 4; w++) {
                expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(w), 3_000));
            }
            expenses.add(variable(CATEGORY, WEEK_START, 30_000)); // 17.5배지만 소액

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.selfRatio()).isNull();
        }

        @Test
        @DisplayName("1.5배 미만이면 null")
        void null_when_below_threshold() {
            // 과거 7주: 매주 50,000 (주당 평균 50,000)
            // 이번 주: 60,000 → 1.2배 → 임계 미만
            List<Expense> expenses = new ArrayList<>();
            for (int w = 1; w <= 7; w++) {
                expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(w), 50_000));
            }
            expenses.add(variable(CATEGORY, WEEK_START, 60_000));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.selfRatio()).isNull();
        }
    }

    // ── 빈도 vs 단가 분해 ──

    @Nested
    @DisplayName("freqPrice — 빈도 vs 단가 로그 분해")
    class FreqPriceTest {

        @Test
        @DisplayName("빈도만 증가하면 FREQUENCY 지배")
        void frequency_dominant_when_only_freq_increases() {
            // 과거 7주: 주당 1건, 단가 10,000
            List<Expense> expenses = new ArrayList<>();
            for (int w = 1; w <= 7; w++) {
                expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(w), 10_000));
            }
            // 이번 주: 5건, 단가 10,000 (빈도만 5배)
            for (int i = 0; i < 5; i++) {
                expenses.add(variable(CATEGORY, WEEK_START, 10_000));
            }

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.dominantFactor()).isEqualTo("FREQUENCY");
            assertThat(result.freqContribution()).isGreaterThan(0.9);
        }

        @Test
        @DisplayName("단가만 증가하면 UNIT_PRICE 지배")
        void price_dominant_when_only_price_increases() {
            // 과거 7주: 주당 1건, 단가 10,000
            List<Expense> expenses = new ArrayList<>();
            for (int w = 1; w <= 7; w++) {
                expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(w), 10_000));
            }
            // 이번 주: 1건, 단가 50,000 (단가만 5배)
            expenses.add(variable(CATEGORY, WEEK_START, 50_000));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.dominantFactor()).isEqualTo("UNIT_PRICE");
            assertThat(result.priceContribution()).isGreaterThan(0.9);
        }

        @Test
        @DisplayName("빈도 감소 + 단가 증가 → 단가가 100% 원인 (감소는 상쇄 요인)")
        void decrease_is_offset_not_cause() {
            // 과거 7주: 주당 6건, 단가 20,000
            List<Expense> expenses = new ArrayList<>();
            for (int w = 1; w <= 7; w++) {
                for (int i = 0; i < 6; i++) {
                    expenses.add(variable(CATEGORY, WEEK_START.minusWeeks(w), 20_000));
                }
            }
            // 이번 주: 3건, 단가 40,000 (빈도 감소, 단가 증가)
            for (int i = 0; i < 3; i++) {
                expenses.add(variable(CATEGORY, WEEK_START, 40_000));
            }

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            // 빈도 감소(log<0) → max(x,0)=0, 단가만 기여 → UNIT_PRICE
            assertThat(result.dominantFactor()).isEqualTo("UNIT_PRICE");
            assertThat(result.priceContribution()).isEqualTo(1.0, within(0.01));
            assertThat(result.freqContribution()).isEqualTo(0.0, within(0.01));
        }
    }

    // ── 감정태그 ──

    @Nested
    @DisplayName("emotionCounts — 감정태그 집계")
    class EmotionTest {

        @Test
        @DisplayName("이번 주 변동 지출의 감정태그만 집계")
        void counts_variable_expenses_only() {
            List<Expense> expenses = new ArrayList<>();
            expenses.add(variableWithEmotion(CATEGORY, WEEK_START, 10_000, "REGRET"));
            expenses.add(variableWithEmotion(CATEGORY, WEEK_START.plusDays(1), 10_000, "REGRET"));
            expenses.add(variableWithEmotion(CATEGORY, WEEK_START.plusDays(2), 10_000, "IMPULSE"));
            // 고정 지출 — 집계 대상 아님
            expenses.add(recurringWithEmotion(CATEGORY, WEEK_START, 50_000, "NECESSARY"));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.emotionCounts()).containsEntry("REGRET", 2L);
            assertThat(result.emotionCounts()).containsEntry("IMPULSE", 1L);
            assertThat(result.emotionCounts()).doesNotContainKey("NECESSARY");
        }
    }

    // ── 반복 패턴 ──

    @Nested
    @DisplayName("repeatStreak — 프로필 패턴 매칭")
    class RepeatStreakTest {

        @Test
        @DisplayName("프로필에 해당 카테고리 streak이 있으면 정수로 반환")
        void returns_streak_from_profile() {
            UserProfile profile = UserProfile.builder()
                    .userId(1L)
                    .repeatPatterns("{\"1\":{\"streak\":4,\"since\":\"2026-06\"}}")
                    .build();
            List<Expense> expenses = new ArrayList<>();
            expenses.add(variable(CATEGORY, WEEK_START, 50_000));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, profile);
            assertThat(result.repeatStreak()).isEqualTo(4);
        }

        @Test
        @DisplayName("프로필에 해당 카테고리가 없으면 null")
        void null_when_no_pattern() {
            UserProfile profile =
                    UserProfile.builder().userId(1L).repeatPatterns("{}").build();
            List<Expense> expenses = new ArrayList<>();
            expenses.add(variable(CATEGORY, WEEK_START, 50_000));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, profile);
            assertThat(result.repeatStreak()).isNull();
        }

        @Test
        @DisplayName("프로필이 null이면 null")
        void null_when_profile_null() {
            List<Expense> expenses = new ArrayList<>();
            expenses.add(variable(CATEGORY, WEEK_START, 50_000));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.repeatStreak()).isNull();
        }
    }

    // ── 메모 ──

    @Nested
    @DisplayName("memos — 메모 원문 추출")
    class MemoTest {

        @Test
        @DisplayName("이번 주 + 지난주 메모를 최신순으로 반환")
        void extracts_this_and_last_week_memos() {
            List<Expense> expenses = new ArrayList<>();
            expenses.add(variableWithMemo(CATEGORY, WEEK_START, 10_000, "이번주 메모"));
            expenses.add(variableWithMemo(CATEGORY, WEEK_START.minusDays(3), 20_000, "지난주 메모"));
            // 2주 전 — 범위 밖
            expenses.add(variableWithMemo(CATEGORY, WEEK_START.minusDays(15), 30_000, "오래된 메모"));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.hasMemo()).isTrue();
            assertThat(result.memos()).hasSize(2);
            assertThat(result.memos().get(0).text()).isEqualTo("이번주 메모"); // 최신순
            assertThat(result.memos().get(0).amount()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("메모 없으면 hasMemo=false, 빈 리스트")
        void empty_when_no_memos() {
            List<Expense> expenses = new ArrayList<>();
            expenses.add(variable(CATEGORY, WEEK_START, 10_000));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.hasMemo()).isFalse();
            assertThat(result.memos()).isEmpty();
        }

        @Test
        @DisplayName("고정 지출 메모도 포함 (모든 유형)")
        void includes_recurring_expense_memos() {
            List<Expense> expenses = new ArrayList<>();
            expenses.add(recurringWithMemo(CATEGORY, WEEK_START, 50_000, "월세 메모"));

            CauseSignals result = assembler.assemble(CATEGORY, expenses, WEEK_START, WEEK_END, null);
            assertThat(result.hasMemo()).isTrue();
            assertThat(result.memos().get(0).text()).isEqualTo("월세 메모");
        }
    }

    // ── 헬퍼 ──

    private static Expense variable(int categoryId, LocalDate date, long amount) {
        return Expense.builder()
                .categoryId(categoryId)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(date.atStartOfDay())
                .recurring(false)
                .planned(false)
                .build();
    }

    private static Expense variableWithEmotion(int categoryId, LocalDate date, long amount, String emotion) {
        return Expense.builder()
                .categoryId(categoryId)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(date.atStartOfDay())
                .emotion(emotion)
                .recurring(false)
                .planned(false)
                .build();
    }

    private static Expense variableWithMemo(int categoryId, LocalDate date, long amount, String memo) {
        return Expense.builder()
                .categoryId(categoryId)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(date.atStartOfDay())
                .memo(memo)
                .recurring(false)
                .planned(false)
                .build();
    }

    private static Expense recurringWithEmotion(int categoryId, LocalDate date, long amount, String emotion) {
        return Expense.builder()
                .categoryId(categoryId)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(date.atStartOfDay())
                .emotion(emotion)
                .recurring(true)
                .planned(false)
                .build();
    }

    private static Expense recurringWithMemo(int categoryId, LocalDate date, long amount, String memo) {
        return Expense.builder()
                .categoryId(categoryId)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(date.atStartOfDay())
                .memo(memo)
                .recurring(true)
                .planned(false)
                .build();
    }
}
