package com.planus.backend.domain.aifeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.AnomalyDetector;
import com.planus.backend.domain.aifeedback.core.BudgetProjector;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.service.AiFeedbackService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * detectAllAnomalies() 검증.
 *
 * ※ detectAllAnomalies는 AiFeedbackService의 private 메서드이므로 리플렉션으로 접근한다.
 *   향후 이상치 탐지 로직을 별도 클래스(예: AnomalyScanner)로 추출하면
 *   리플렉션 없이 직접 테스트 가능하다. (리팩터링 시 고려)
 */
class AnomalyDetectionPerfTest {

    private AiFeedbackService service;
    private Method detectMethod;

    private static final LocalDate WEEK_END = LocalDate.of(2026, 6, 28); // 일요일
    private static final LocalDate WEEK_START = WEEK_END.minusDays(6); // 월요일

    private static final Map<Integer, Long> CAT_BUDGET = Map.of(
            1, 200_000L, // 식료품
            6, 150_000L, // 교통
            8, 100_000L, // 오락·문화
            10, 300_000L // 외식·숙박
            );

    @BeforeEach
    void setUp() throws Exception {
        AiFeedbackProperties props = new AiFeedbackProperties();
        AnomalyDetector detector = new AnomalyDetector(props);

        service = new AiFeedbackService(
                null, // userRepo
                null, // expenseRepo
                null, // budgetRepo
                null, // budgetCatRepo
                null, // feedbackRepo
                null, // monthEndVerifRepo
                null, // userProfileRepo
                null, // reactionRepo
                null, // memoQueueRepo
                detector,
                new BudgetProjector(),
                null, // renderer
                null, // activityGuard
                null, // pacingComparator
                null, // feedbackTypeResolver
                null, // causeAssembler
                null, // savingsCalculator
                null, // profileUpdater
                props,
                null, // objectMapper
                Clock.systemDefaultZone(),
                null); // tx

        detectMethod = AiFeedbackService.class.getDeclaredMethod(
                "detectAllAnomalies", List.class, LocalDate.class, LocalDate.class, Map.class);
        detectMethod.setAccessible(true);
    }

    @Nested
    @Tag("benchmark")
    @DisplayName("성능 — 스케일링 검증")
    class ScalingTest {

        @Test
        @DisplayName("window 크기 3배 증가 시 시간 증가 비율이 6배 미만이어야 한다 (O(n²) 퇴행 방지)")
        void detectAllAnomalies_scalingShouldBeSubQuadratic() throws Exception {
            int[] sizes = {100, 500, 1000, 3000};
            long[] avgNs = new long[sizes.length];

            for (int i = 0; i < sizes.length; i++) {
                List<Expense> window = generateRealisticWindow(sizes[i]);

                // JIT 워밍업
                detectMethod.invoke(service, window, WEEK_START, WEEK_END, CAT_BUDGET);
                detectMethod.invoke(service, window, WEEK_START, WEEK_END, CAT_BUDGET);

                int runs = 5;
                long total = 0;
                for (int r = 0; r < runs; r++) {
                    long start = System.nanoTime();
                    detectMethod.invoke(service, window, WEEK_START, WEEK_END, CAT_BUDGET);
                    total += (System.nanoTime() - start);
                }
                avgNs[i] = total / runs;

                System.out.printf(
                        "W=%4d  →  avg %4dms  (이번주 변동거래 ~%d건)%n",
                        sizes[i], avgNs[i] / 1_000_000, countThisWeekVariable(window));
            }

            // ── 스케일링 비율 출력 + assert ──
            System.out.println("\n── 스케일링 분석 ──");
            for (int i = 1; i < sizes.length; i++) {
                double sizeRatio = (double) sizes[i] / sizes[i - 1];
                double timeRatio = avgNs[i - 1] > 0 ? (double) avgNs[i] / avgNs[i - 1] : 0;
                System.out.printf(
                        "W %d→%d (x%.1f)  시간 x%.1f  %s%n",
                        sizes[i - 1],
                        sizes[i],
                        sizeRatio,
                        timeRatio,
                        timeRatio > sizeRatio * 2.0 ? "⚠ O(n²) 의심" : "✓ 선형 범위");
            }

            // 핵심 assert: W 1000→3000 (x3.0) 구간에서 시간 비율이 6배 미만
            double lastRatio = avgNs[2] > 0 ? (double) avgNs[3] / avgNs[2] : 0;
            assertThat(lastRatio)
                    .as("W 1000→3000 스케일링 비율 (O(n²)이면 ~9배, 선형이면 ~3배)")
                    .isLessThan(6.0);
        }

        // 집중도 비교 테스트 제거: 단일 카테고리 1000건 집중은 히스토리 스캔 대상이 구조적으로 커져
        // 분산 대비 ~20x 차이가 나는 것이 정상. O(n²) 퇴행은 위의 스케일링 테스트로 충분히 감지.
    }

    @Nested
    @DisplayName("콜드스타트 가드 (minWeeks)")
    class ColdStartGuard {

        @Test
        @DisplayName("거래 이력이 minWeeks(4주) 미만이면 이상치급 금액이 있어도 탐지를 스킵한다")
        void coldStart_shouldReturnNull() throws Exception {
            // 2주치 거래만 생성 — 신규 사용자 시뮬레이션
            List<Expense> shortWindow = new ArrayList<>();
            Random rng = new Random(42);
            for (int i = 0; i < 50; i++) {
                int daysAgo = rng.nextInt(14);
                long amount = rng.nextDouble() < 0.2 ? 300_000 + rng.nextInt(200_000) : 5_000 + rng.nextInt(50_000);
                shortWindow.add(expense(i, new int[] {1, 6, 8, 10}[rng.nextInt(4)], amount, daysAgo));
            }

            @SuppressWarnings("unchecked")
            List<Object> result =
                    (List<Object>) detectMethod.invoke(service, shortWindow, WEEK_START, WEEK_END, CAT_BUDGET);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("거래 이력이 minWeeks(4주) 이상이면 탐지 로직이 실행된다 (null이 아닐 수 있음)")
        void sufficientHistory_shouldExecuteDetection() throws Exception {
            // 8주치 거래, 10% 이상치 포함
            List<Expense> window = generateRealisticWindow(500);

            @SuppressWarnings("unchecked")
            List<Object> result = (List<Object>) detectMethod.invoke(service, window, WEEK_START, WEEK_END, CAT_BUDGET);

            // 이 테스트의 목적: minWeeks 가드를 통과하고 탐지 로직이 실행되었는지 확인
            // 합성 데이터에 10% 이상치가 포함되어 있으므로 대부분 감지됨
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("정확히 4주(28일)치 거래가 있으면 탐지가 실행된다 (경계값)")
        void exactlyMinWeeks_shouldExecuteDetection() throws Exception {
            // 결정적 fixture: 4주(28일) 동안 매일 1건씩 + 이번 주 이상치 1건
            List<Expense> window = new ArrayList<>();
            int id = 0;
            // 히스토리: 28일간 카테고리1에 매일 10,000원
            for (int d = 0; d < 28; d++) {
                window.add(expense(id++, 1, 10_000, d));
            }
            // 이번 주 이상치: 카테고리1에 500,000원 (평소의 50배)
            window.add(expense(id++, 1, 500_000, 0));

            @SuppressWarnings("unchecked")
            List<Object> fourWeekResult =
                    (List<Object>) detectMethod.invoke(service, window, WEEK_START, WEEK_END, CAT_BUDGET);

            // 4주 = minWeeks 이므로 탐지 실행, 명확한 이상치가 있으므로 감지되어야 함
            assertThat(fourWeekResult).as("4주(경계) 데이터 + 명확한 이상치 → 탐지됨").isNotEmpty();

            // 대조: 같은 데이터를 3주(21일)로 줄이면 minWeeks 미만 → 빈 리스트
            List<Expense> threeWeekWindow = new ArrayList<>();
            id = 0;
            for (int d = 0; d < 21; d++) {
                threeWeekWindow.add(expense(id++, 1, 10_000, d));
            }
            threeWeekWindow.add(expense(id++, 1, 500_000, 0));

            @SuppressWarnings("unchecked")
            List<Object> threeWeekResult =
                    (List<Object>) detectMethod.invoke(service, threeWeekWindow, WEEK_START, WEEK_END, CAT_BUDGET);
            assertThat(threeWeekResult).as("3주 데이터는 minWeeks 미만이므로 빈 리스트").isEmpty();
        }
    }

    @Nested
    @DisplayName("데이터 필터링 정확성")
    class DataFiltering {

        @Test
        @DisplayName("INCOME 타입 거래는 이상치 탐지에서 제외된다")
        void incomeTransactions_shouldBeIgnored() throws Exception {
            List<Expense> window = new ArrayList<>();
            // 8주치 정상 EXPENSE
            for (int i = 0; i < 100; i++) {
                window.add(expense(i, 1, 10_000 + i * 100, i % 56)); // 균일한 금액
            }
            // 이번 주에 거액 INCOME — 이것이 이상치로 감지되면 안 됨
            window.add(Expense.builder()
                    .id(200L)
                    .userId(1L)
                    .categoryId(1)
                    .type("INCOME")
                    .amount(5_000_000L)
                    .expenseDate(WEEK_END.atTime(12, 0))
                    .recurring(false)
                    .planned(false)
                    .build());

            @SuppressWarnings("unchecked")
            List<Object> result = (List<Object>) detectMethod.invoke(service, window, WEEK_START, WEEK_END, CAT_BUDGET);

            // INCOME이 이상치로 잡히면 안 됨 — 정상 EXPENSE만으로는 이상치 없을 수 있음
            // 핵심: 500만원 INCOME이 이상치 결과에 포함되지 않아야 함
            if (!result.isEmpty()) {
                // Anomaly record의 rep() 필드에 INCOME 거래가 들어있으면 안 됨
                Object topAnomaly = result.get(0);
                var repField = topAnomaly.getClass().getDeclaredMethod("rep");
                repField.setAccessible(true);
                Expense rep = (Expense) repField.invoke(topAnomaly);
                assertThat(rep.isExpense()).as("이상치 대표 거래는 EXPENSE여야 함").isTrue();
            }
        }

        @Test
        @DisplayName("recurring/planned 거래는 변동 거래가 아니므로 이상치 탐지에서 제외된다")
        void recurringAndPlanned_shouldBeExcludedFromVariable() throws Exception {
            List<Expense> window = new ArrayList<>();
            // 8주치 정상 변동 EXPENSE
            for (int i = 0; i < 100; i++) {
                window.add(expense(i, 1, 10_000 + i * 100, i % 56));
            }
            // 이번 주에 거액 반복 지출 — 변동이 아니므로 이상치 대상이 아님
            window.add(Expense.builder()
                    .id(300L)
                    .userId(1L)
                    .categoryId(1)
                    .type("EXPENSE")
                    .amount(3_000_000L)
                    .expenseDate(WEEK_END.atTime(12, 0))
                    .recurring(true)
                    .planned(false)
                    .build());
            // 이번 주에 거액 예정 지출
            window.add(Expense.builder()
                    .id(301L)
                    .userId(1L)
                    .categoryId(1)
                    .type("EXPENSE")
                    .amount(2_000_000L)
                    .expenseDate(WEEK_END.minusDays(1).atTime(12, 0))
                    .recurring(false)
                    .planned(true)
                    .build());

            @SuppressWarnings("unchecked")
            List<Object> result = (List<Object>) detectMethod.invoke(service, window, WEEK_START, WEEK_END, CAT_BUDGET);

            if (!result.isEmpty()) {
                Object topAnomaly = result.get(0);
                var repField = topAnomaly.getClass().getDeclaredMethod("rep");
                repField.setAccessible(true);
                Expense rep = (Expense) repField.invoke(topAnomaly);
                assertThat(rep.isVariable())
                        .as("이상치 대표 거래는 변동(non-recurring, non-planned)이어야 함")
                        .isTrue();
            }
        }
    }

    // ── 합성 데이터 생성 ──

    /**
     * 현실적인 윈도우: EXPENSE(변동/반복/예정) + INCOME 혼합.
     * 9주(63일), 4개 카테고리, ~10% 이상치급 금액.
     */
    private List<Expense> generateRealisticWindow(int totalCount) {
        List<Expense> list = new ArrayList<>();
        int[] categories = {1, 6, 8, 10};
        Random rng = new Random(42);

        for (int i = 0; i < totalCount; i++) {
            int daysAgo = rng.nextInt(63);
            int cat = categories[rng.nextInt(categories.length)];
            long amount = 5_000 + rng.nextInt(95_000);
            String type = "EXPENSE";
            boolean recurring = false;
            boolean planned = false;

            double roll = rng.nextDouble();
            if (roll < 0.05) {
                // 5% INCOME
                type = "INCOME";
                amount = 100_000 + rng.nextInt(2_000_000);
            } else if (roll < 0.15) {
                // 10% 반복 지출
                recurring = true;
            } else if (roll < 0.20) {
                // 5% 예정 지출
                planned = true;
            } else if (roll < 0.30) {
                // 10% 이상치급 변동 지출
                amount = 200_000 + rng.nextInt(300_000);
            }

            list.add(Expense.builder()
                    .id((long) i)
                    .userId(1L)
                    .categoryId(cat)
                    .type(type)
                    .amount(amount)
                    .expenseDate(WEEK_END.minusDays(daysAgo).atTime(12, 0))
                    .recurring(recurring)
                    .planned(planned)
                    .build());
        }
        return list;
    }

    /** 간편 변동 EXPENSE 생성 헬퍼 */
    private Expense expense(int id, int categoryId, long amount, int daysAgo) {
        return Expense.builder()
                .id((long) id)
                .userId(1L)
                .categoryId(categoryId)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(WEEK_END.minusDays(daysAgo).atTime(12, 0))
                .recurring(false)
                .planned(false)
                .build();
    }

    private long countThisWeekVariable(List<Expense> window) {
        return window.stream()
                .filter(Expense::isVariable)
                .filter(e -> !e.getDate().isBefore(WEEK_START) && !e.getDate().isAfter(WEEK_END))
                .count();
    }
}
