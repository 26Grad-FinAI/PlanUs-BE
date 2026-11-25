package com.planus.backend.domain.aifeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.action.ActionPlan;
import com.planus.backend.domain.aifeedback.action.SavingsActionCalculator;
import com.planus.backend.domain.aifeedback.cause.CauseSignalAssembler;
import com.planus.backend.domain.aifeedback.cause.CauseSignals;
import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.*;
import com.planus.backend.domain.aifeedback.core.RobustStats;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.llm.AnthropicLlmClient;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer;
import com.planus.backend.domain.aifeedback.llm.LlmClient;
import com.planus.backend.domain.aifeedback.llm.LlmRenderMeter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Walk-forward 검증 — 실거래 데이터 기반 파이프라인 컴포넌트 검증.
 *
 * <p>데이터 분할:
 * <ul>
 *   <li>Warmup (1~16주차): 파이프라인 베이스라인 구축용
 *   <li>검증 (17주차~): walk-forward 방식으로 매주 피드백 생성 후 결과 출력
 * </ul>
 *
 * <p>검증 지표:
 * <ul>
 *   <li>FeedbackType 분포 (ALERT / POSITIVE / LOW_DATA)
 *   <li>카테고리별 이상 탐지 건수
 *   <li>BudgetProjector 예측 오차 (MAPE)
 * </ul>
 */
@DisplayName("Walk-forward 검증 — 실거래 데이터 기반 파이프라인 검증")
class WalkForwardValidationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final long USER_ID = 1L;
    private static final int WARMUP_WEEKS = 16;
    private static final LocalDate DATA_START = LocalDate.of(2026, 2, 1); // 1월 제외

    private static final long MONTHLY_INCOME = 1_200_000L; // 월 수입 120만원
    private static final long SAVINGS_GOAL = 200_000L; // 저축 목표 20만원
    private static final long AVAILABLE_BUDGET = MONTHLY_INCOME - SAVINGS_GOAL; // 가용예산 100만원

    /** LLM 검증 반복 횟수. 환경변수 LLM_REPEAT로 지정 (기본 1). 숫자 후검증 통과율 측정용. */
    private static final int LLM_REPEAT = Math.max(
            1, Integer.parseInt(Optional.ofNullable(System.getenv("LLM_REPEAT")).orElse("1")));

    /**
     * LLM 검증 대상 주차 제한. 환경변수 LLM_WEEKS (예: "35" 또는 "18,35"). 비어 있으면 전체 주차.
     * 특정 상황(예: 35주차 횟수 오류)만 반복 측정해 표본을 늘릴 때 사용한다 (#111).
     */
    private static final Set<Integer> LLM_WEEKS = Optional.ofNullable(System.getenv("LLM_WEEKS"))
            .filter(v -> !v.isBlank())
            .map(v -> Arrays.stream(v.split(","))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .collect(Collectors.toSet()))
            .orElse(Set.of());

    // YearMonth → (categoryId → 예산)
    private static Map<YearMonth, Map<Integer, Long>> BUDGETS_BY_MONTH;

    private static List<Expense> ALL_EXPENSES;
    private static LocalDate FIRST_MONDAY;

    private static AiFeedbackProperties props;
    private static AnomalyDetector anomalyDetector;
    private static BudgetProjector budgetProjector;
    private static PacingComparator pacingComparator;
    private static ActivityGuard activityGuard;
    private static FeedbackTypeResolver feedbackTypeResolver;
    private static CauseSignalAssembler causeSignalAssembler;
    private static SavingsActionCalculator savingsActionCalculator;

    @BeforeAll
    static void setup() throws Exception {
        ALL_EXPENSES = loadTransactions();
        BUDGETS_BY_MONTH = loadBudgets();

        assertThat(ALL_EXPENSES).isNotEmpty();
        assertThat(BUDGETS_BY_MONTH).isNotEmpty();

        LocalDate dataStart = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        FIRST_MONDAY = dataStart.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        props = new AiFeedbackProperties();
        anomalyDetector = new AnomalyDetector(props);
        budgetProjector = new BudgetProjector();
        pacingComparator = new PacingComparator();
        activityGuard = new ActivityGuard(props);
        feedbackTypeResolver = new FeedbackTypeResolver();
        causeSignalAssembler = new CauseSignalAssembler(new ObjectMapper());
        savingsActionCalculator = new SavingsActionCalculator(props);

        System.out.printf("%n=== 데이터 현황 ===%n");
        System.out.printf("총 거래 건수 : %d건%n", ALL_EXPENSES.size());
        System.out.printf("기간         : %s ~ %s%n", dataStart, dataEnd);
        System.out.printf("월 수입      : %,d원  저축목표: %,d원  가용예산: %,d원%n", MONTHLY_INCOME, SAVINGS_GOAL, AVAILABLE_BUDGET);
        System.out.printf("워밍업       : 1~%d주차 (약 4개월)%n", WARMUP_WEEKS);
        System.out.printf("검증 시작    : %d주차~%n%n", WARMUP_WEEKS + 1);
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 1] Walk-forward FeedbackType 결정
    // ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[검증 1] Walk-forward: 주차별 FeedbackType 분포")
    void walkForward_feedbackType() {
        System.out.println("=== [검증 1] FeedbackType Walk-forward 결과 ===");
        System.out.printf(
                "%-5s %-22s %-12s %-14s %-8s %-8s%n", "주차", "기간", "FeedbackType", "savingsImpact", "이상탐지", "LOW_DATA");
        System.out.println("-".repeat(75));

        int alertCount = 0, positiveCount = 0, lowDataCount = 0;
        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;

            Map<Integer, Long> catBudget = budgetForMonth(weekEnd);
            List<Expense> history = expensesUpTo(weekEnd);
            List<Expense> thisWeek = expensesInRange(history, weekStart, weekEnd);

            boolean isLowData = checkLowData(week, thisWeek.size());
            boolean hasHighAnomaly = !isLowData && detectHighAnomaly(history, thisWeek, weekStart, catBudget);
            long savingsImpact = calcSavingsImpact(history, weekEnd);
            boolean hasOverBudget = hasOverBudgetCategory(history, weekEnd, catBudget);

            FeedbackType type = feedbackTypeResolver.resolve(isLowData, savingsImpact, hasHighAnomaly, hasOverBudget);

            switch (type) {
                case ALERT -> alertCount++;
                case POSITIVE -> positiveCount++;
                case LOW_DATA -> lowDataCount++;
            }

            System.out.printf(
                    "%-5d %-22s %-12s %-14s %-8s %-8s%n",
                    week,
                    weekStart + " ~ " + weekEnd,
                    type,
                    String.format("%,d원", savingsImpact),
                    hasHighAnomaly ? "YES" : "-",
                    isLowData ? "YES" : "-");
        }

        System.out.println("-".repeat(75));
        System.out.printf("ALERT: %d건 / POSITIVE: %d건 / LOW_DATA: %d건%n%n", alertCount, positiveCount, lowDataCount);

        assertThat(alertCount + positiveCount + lowDataCount).isGreaterThan(0);
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 2] 카테고리별 이상 탐지 건수
    // ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[검증 2] AnomalyDetector: 카테고리별 이상 탐지 현황")
    void anomalyDetector_categoryBreakdown() {
        Map<Integer, Integer> countByCategory = new TreeMap<>();
        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;

            Map<Integer, Long> catBudget = budgetForMonth(weekEnd);
            List<Expense> history = expensesUpTo(weekEnd);
            List<Expense> thisWeek = expensesInRange(history, weekStart, weekEnd);

            for (int catId : catBudget.keySet()) {
                if (anomalyDetector.isSpikeProne(catId)) continue;
                long budget = catBudget.getOrDefault(catId, 100_000L);

                double[] catHistory = history.stream()
                        .filter(e -> e.isExpense()
                                && Objects.equals(e.getCategoryId(), catId)
                                && e.getDate().isBefore(weekStart)
                                && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                        .mapToDouble(Expense::getAmount)
                        .toArray();

                for (Expense e : thisWeek) {
                    if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                    OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), catHistory, budget);
                    if (z.isPresent()) countByCategory.merge(catId, 1, Integer::sum);
                }
            }
        }

        System.out.println("=== [검증 2] 카테고리별 이상 탐지 건수 ===");
        countByCategory.forEach(
                (catId, cnt) -> System.out.printf("  [%2d] %-18s : %d건%n", catId, Categories.name(catId), cnt));
        System.out.println();

        assertThat(countByCategory).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 2-1] 이상 탐지 항목 상세
    // ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[검증 2-1] AnomalyDetector: 이상 탐지 항목 상세")
    void anomalyDetector_detail() {
        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        System.out.println("=== [검증 2-1] 이상 탐지 항목 상세 ===");
        System.out.printf(
                "%-12s %-10s %-18s %-8s %-8s %-8s %-20s%n", "날짜", "카테고리", "금액", "중앙값", "z-score", "신뢰도", "메모");
        System.out.println("-".repeat(100));

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;

            Map<Integer, Long> catBudget = budgetForMonth(weekEnd);
            List<Expense> history = expensesUpTo(weekEnd);
            List<Expense> thisWeek = expensesInRange(history, weekStart, weekEnd);

            for (int catId : catBudget.keySet()) {
                if (anomalyDetector.isSpikeProne(catId)) continue;
                long budget = catBudget.getOrDefault(catId, 100_000L);

                double[] catHistory = history.stream()
                        .filter(e -> e.isExpense()
                                && Objects.equals(e.getCategoryId(), catId)
                                && e.getDate().isBefore(weekStart)
                                && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                        .mapToDouble(Expense::getAmount)
                        .toArray();

                double median = catHistory.length > 0 ? RobustStats.median(catHistory) : 0;

                for (Expense e : thisWeek) {
                    if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                    OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), catHistory, budget);
                    if (z.isEmpty()) continue;

                    Confidence confidence = Confidence.of(
                            z.getAsDouble(),
                            catHistory.length,
                            props.getConfHighMinSamples(),
                            props.getConfMediumMinSamples());

                    System.out.printf(
                            "%-12s %-10s %-18s %-8s %-8s %-8s %-20s%n",
                            e.getDate(),
                            Categories.name(catId),
                            String.format("%,d원", e.getAmount()),
                            String.format("%,d원", (long) median),
                            String.format("%.2f", z.getAsDouble()),
                            confidence,
                            e.getMemo() == null || e.getMemo().isBlank() ? "-" : e.getMemo());
                }
            }
        }
        System.out.println();
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 3] BudgetProjector 예측 오차 (경과일 구간별, 단순 예측과 비교)
    // ──────────────────────────────────────────────────────────────
    /**
     * 월말 예측 오차를 경과일 구간별로 나누고 단순 예측과 비교한다 (#141).
     *
     * <p>전체 평균(MAPE)은 월말에 가까운 주차(거의 확정)와 월초 주차(거의 추측)를 섞어 평균내므로 오차가 가려진다.
     * 운영({@code AiFeedbackService})과 같은 입력을 쓴다: 평소 일평균 = 전월 변동지출 ÷ 전월 일수. 고정 지출 추정은 따로라서 변동지출만 비교한다.
     * 단순 예측: 선형 외삽(지금까지 일평균 × 월 일수), 전월 그대로(지난달 변동지출).
     */
    @Test
    @DisplayName("[검증 3] BudgetProjector: 경과일 구간별 오차, 단순 예측과 비교")
    void budgetProjector_mapeByElapsedDays() {
        LocalDate dataStart = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        String[] bucketNames = {"1~7일", "8~14일", "15~21일", "22일~"};
        double[][] sums = new double[4][4]; // [구간][프로젝터, 선형 외삽, 전월 그대로, 프로젝터(전월 있는 표본)]
        int[] counts = new int[4];
        int[] prevCounts = new int[4];

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            if (weekEnd.isAfter(dataEnd)) break;
            LocalDate monthStart = weekEnd.withDayOfMonth(1);
            LocalDate monthEnd = weekEnd.withDayOfMonth(weekEnd.lengthOfMonth());
            if (monthEnd.isAfter(dataEnd)) continue;

            int daysElapsed = (int) java.time.temporal.ChronoUnit.DAYS.between(monthStart, weekEnd) + 1;
            int daysInMonth = weekEnd.lengthOfMonth();
            int bucket = Math.min(3, (daysElapsed - 1) / 7);

            long variableMtd = monthSum(monthStart, weekEnd, true);
            long actual = monthSum(monthStart, monthEnd, true);
            if (actual <= 0) continue;

            LocalDate prevStart = monthStart.minusMonths(1);
            boolean hasPrev = !prevStart.isBefore(dataStart.withDayOfMonth(1));
            long prevTotal = hasPrev ? monthSum(prevStart, monthStart.minusDays(1), true) : 0;
            double priorDailyRate = prevTotal > 0 ? (double) prevTotal / prevStart.lengthOfMonth() : 0.0;

            long projector = budgetProjector
                    .project(variableMtd, 0, 0, daysElapsed, daysInMonth, priorDailyRate, null, null)
                    .predictedMonthEndWon();
            double linear = (double) variableMtd / daysElapsed * daysInMonth;

            sums[bucket][0] += pctError(projector, actual);
            sums[bucket][1] += pctError(linear, actual);
            counts[bucket]++;
            if (prevTotal > 0) {
                sums[bucket][2] += pctError(prevTotal, actual);
                sums[bucket][3] += pctError(projector, actual);
                prevCounts[bucket]++;
            }
        }

        System.out.println("=== [검증 3] 경과일 구간별 월말 변동지출 예측 오차 (MAPE %) ===");
        System.out.printf(
                "%-8s %-4s %-10s %-10s | %-4s %-10s %-10s%n", "구간", "n", "프로젝터", "선형외삽", "n", "프로젝터", "전월그대로");
        for (int b = 0; b < 4; b++) {
            if (counts[b] == 0) continue;
            System.out.printf(
                    "%-8s %-4d %-10.1f %-10.1f | %-4d %-10.1f %-10.1f%n",
                    bucketNames[b],
                    counts[b],
                    sums[b][0] / counts[b],
                    sums[b][1] / counts[b],
                    prevCounts[b],
                    prevCounts[b] > 0 ? sums[b][3] / prevCounts[b] : Double.NaN,
                    prevCounts[b] > 0 ? sums[b][2] / prevCounts[b] : Double.NaN);
        }

        // 표본이 구간당 5~7개월이라 구간별 순위는 참고용이다. 그래도 월초(전월 평소를 섞는 구간)가 선형 외삽보다 나쁘면
        // 혼합식이 의미가 없고, 어느 구간이든 20%를 넘으면 월말 초과 판정을 믿을 수 없다.
        assertThat(sums[0][0] / counts[0]).isLessThan(sums[0][1] / counts[0]);
        for (int b = 0; b < 4; b++) {
            if (counts[b] > 0) assertThat(sums[b][0] / counts[b]).isLessThan(20.0);
        }
    }

    /**
     * 추세 이상치의 '그 앞 4주' 구간이 최근 2주 바로 앞 한 주(w-2)를 건너뛰는 것이 판정에 미치는 영향 (#141).
     *
     * <p>현재: 최근 2주(w-1, w) vs 그 앞 4주(w-6..w-3). 대안: 바로 앞 4주(w-5..w-2). 두 방식으로 확정되는 (주, 카테고리) 수와
     * 서로 다르게 판정되는 경우를 센다. 정답 라벨이 없어 어느 쪽이 맞는지는 말하지 않고, 판정이 얼마나 갈리는지만 기록한다.
     */
    @Test
    @DisplayName("[검증 6] 추세 구간 w-2 건너뜀: 현재 방식과 바로 앞 4주 방식의 판정 차이")
    void trend_gapWeekEffect() {
        int len = props.getBaselineWeeks();
        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        int both = 0, onlyCurrent = 0, onlyAlt = 0, samples = 0;
        List<String> diffs = new ArrayList<>();

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            if (weekEnd.isAfter(dataEnd)) break;
            Map<Integer, Long> budgets = budgetForMonth(weekEnd);
            for (int cat : budgets.keySet()) {
                if (!anomalyDetector.isHighFreq(cat) || anomalyDetector.isSpikeProne(cat)) continue;
                double[] weekly = new double[len];
                for (int i = 0; i < len; i++) {
                    LocalDate end = weekEnd.minusWeeks(len - 1L - i);
                    LocalDate start = end.minusDays(6);
                    weekly[i] = ALL_EXPENSES.stream()
                            .filter(e -> e.isExpense()
                                    && e.isVariable()
                                    && e.getCategoryId() == cat
                                    && !e.getDate().isBefore(start)
                                    && !e.getDate().isAfter(end))
                            .mapToLong(Expense::getAmount)
                            .sum();
                }
                long budget = budgets.getOrDefault(cat, 0L);
                boolean current = anomalyDetector.trendConfirmed(weekly, len - 1, budget);
                boolean alt = risingContiguous(weekly, len - 1, budget) && risingContiguous(weekly, len - 2, budget);
                samples++;
                if (current && alt) both++;
                else if (current) {
                    onlyCurrent++;
                    diffs.add("현재만 " + weekEnd + " " + Categories.name(cat));
                } else if (alt) {
                    onlyAlt++;
                    diffs.add("바로앞4주만 " + weekEnd + " " + Categories.name(cat));
                }
            }
        }
        System.out.println("=== [검증 6] 추세 구간 w-2 건너뜀 영향 ===");
        System.out.printf(
                "표본 %d (주×고빈도 카테고리) | 둘 다 확정 %d | 현재만 %d | 바로 앞 4주만 %d%n", samples, both, onlyCurrent, onlyAlt);
        diffs.forEach(d -> System.out.println("  " + d));
        assertThat(samples).isPositive();
    }

    /**
     * 이상치 임계값 보정용 라벨링 시트를 만든다 (#141). 환경변수 {@code ANOMALY_LABEL_DIR}이 있을 때만 실행한다 — 개인 거래 내역이 들어가므로
     * 저장소 밖 폴더에 쓴다.
     *
     * <p>후보 종류: 단발(modified-z), 단발(중앙값 배수), 단발(과거 거의 없음), 추세(확정), 추세(확정 직전), 탐지 안 된 큰 거래(재현율 확인용).
     * 시트에는 현재 판정·신뢰도를 숨기고(블라인드), 키 파일에만 적는다.
     */
    @Test
    @DisplayName("이상치 라벨링 시트 내보내기 (환경변수 ANOMALY_LABEL_DIR)")
    void exportAnomalyLabelSheet() throws Exception {
        String dir = System.getenv("ANOMALY_LABEL_DIR");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(), "ANOMALY_LABEL_DIR 없음");
        int len = props.getBaselineWeeks();
        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Random rnd = new Random(141);
        // 종류 -> [날짜, 카테고리, 설명, 현재판정]
        Map<String, List<String[]>> pool = new LinkedHashMap<>();

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;
            Map<Integer, Long> budgets = budgetForMonth(weekEnd);

            for (Expense e : ALL_EXPENSES) {
                if (!e.isExpense() || !e.isVariable()) continue;
                if (e.getDate().isBefore(weekStart) || e.getDate().isAfter(weekEnd)) continue;
                int cat = e.getCategoryId();
                if (anomalyDetector.isSpikeProne(cat)) continue;
                long budget = budgets.getOrDefault(cat, 0L);
                double[] hist = ALL_EXPENSES.stream()
                        .filter(x -> x.isExpense()
                                && x.isVariable()
                                && Objects.equals(x.getCategoryId(), cat)
                                && x.getDate().isBefore(e.getDate())
                                && ChronoUnit.DAYS.between(x.getDate(), e.getDate()) <= props.getBaselineWeeks() * 7L
                                && (!props.isBimodal(cat) || x.getAmount() >= props.getBimodalMin()))
                        .mapToDouble(Expense::getAmount)
                        .toArray();
                OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), hist, budget);
                double med = hist.length > 0 ? RobustStats.median(hist) : 0;
                double max = hist.length > 0 ? Arrays.stream(hist).max().orElse(0) : 0;
                String desc = String.format(
                        "%,d원 (%s%s) — 직전 %d주 같은 카테고리 %d건, 중앙값 %,d원, 최대 %,d원",
                        e.getAmount(),
                        e.getMemo() == null || e.getMemo().isBlank() ? "메모 없음" : "메모: " + e.getMemo(),
                        e.getEmotion() == null ? "" : ", 감정 " + e.getEmotion(),
                        props.getBaselineWeeks(),
                        hist.length,
                        (long) med,
                        (long) max);
                String kind;
                String verdict;
                if (z.isPresent()) {
                    boolean sparse = hist.length < 2;
                    boolean lowSpread = !sparse && RobustStats.mad(hist) <= med * 0.10;
                    kind = sparse ? "단발-과거거의없음" : lowSpread ? "단발-중앙값배수" : "단발-modifiedZ";
                    verdict = String.format(
                            "%s mag=%.2f 신뢰도=%s",
                            kind,
                            z.getAsDouble(),
                            Confidence.of(
                                    z.getAsDouble(),
                                    hist.length,
                                    props.getConfHighMinSamples(),
                                    props.getConfMediumMinSamples()));
                } else if (hist.length >= 2 && e.getAmount() >= med * 2 && e.getAmount() >= 30_000) {
                    kind = "탐지안됨-큰거래";
                    verdict = "탐지 안 됨";
                } else continue;
                pool.computeIfAbsent(kind, k -> new ArrayList<>())
                        .add(new String[] {e.getDate().toString(), Categories.name(cat), "이번 주 지출 1건: " + desc, verdict
                        });
            }

            for (int cat : budgets.keySet()) {
                if (!anomalyDetector.isHighFreq(cat) || anomalyDetector.isSpikeProne(cat)) continue;
                double[] weekly = new double[len];
                for (int i = 0; i < len; i++) {
                    LocalDate end = weekEnd.minusWeeks(len - 1L - i);
                    LocalDate start = end.minusDays(6);
                    int c = cat;
                    weekly[i] = ALL_EXPENSES.stream()
                            .filter(x -> x.isExpense()
                                    && x.isVariable()
                                    && x.getCategoryId() == c
                                    && !x.getDate().isBefore(start)
                                    && !x.getDate().isAfter(end))
                            .mapToLong(Expense::getAmount)
                            .sum();
                }
                long budget = budgets.getOrDefault(cat, 0L);
                boolean confirmed = anomalyDetector.trendConfirmed(weekly, len - 1, budget);
                AnomalyDetector.TrendStats t = anomalyDetector.trendStats(weekly, len - 1);
                if (t == null) continue;
                boolean near = !confirmed && t.priorMean() > 0 && t.ratio() >= 1.2 && t.recentMean() >= 30_000;
                if (!confirmed && !near) continue;
                String series = Arrays.stream(weekly)
                        .mapToObj(v -> String.format("%,d", (long) v))
                        .collect(Collectors.joining(" → "));
                String desc = String.format(
                        "최근 %d주 주간 지출(오래된 순): %s원 — 최근 2주 평균 %,d원, 그 앞 4주 평균 %,d원 (%.2f배)",
                        len, series, (long) t.recentMean(), (long) t.priorMean(), t.ratio());
                String kind = confirmed ? "추세-확정" : "추세-확정직전";
                String verdict = confirmed
                        ? String.format(
                                "추세 확정 mag=%.2f 신뢰도=%s",
                                t.ratio(),
                                Confidence.of(
                                        t.ratio(), 99, props.getConfHighMinSamples(), props.getConfMediumMinSamples()))
                        : "추세 확정 아님";
                pool.computeIfAbsent(kind, k -> new ArrayList<>())
                        .add(new String[] {weekEnd.toString(), Categories.name(cat), desc, verdict});
            }
        }

        Map<String, Integer> quota = Map.of(
                "단발-modifiedZ", 16,
                "단발-중앙값배수", 8,
                "단발-과거거의없음", 6,
                "추세-확정", 14,
                "추세-확정직전", 8,
                "탐지안됨-큰거래", 10);
        List<String[]> picked = new ArrayList<>(); // [종류, 날짜, 카테고리, 설명, 현재판정]
        for (var en : pool.entrySet()) {
            List<String[]> rows = en.getValue();
            Collections.shuffle(rows, rnd);
            rows.stream()
                    .limit(quota.getOrDefault(en.getKey(), 0))
                    .forEach(r -> picked.add(new String[] {en.getKey(), r[0], r[1], r[2], r[3]}));
        }
        Collections.shuffle(picked, rnd);

        java.nio.file.Path out = java.nio.file.Path.of(dir);
        java.nio.file.Files.createDirectories(out);
        StringBuilder sheet = new StringBuilder("﻿id,날짜(주 마지막 날),카테고리,내용,알려줄 만한가(O/X/?),메모\n");
        StringBuilder key = new StringBuilder("﻿id,종류,현재 판정\n");
        for (int i = 0; i < picked.size(); i++) {
            String id = String.format("L%03d", i + 1);
            String[] r = picked.get(i);
            sheet.append(id)
                    .append(',')
                    .append(r[1])
                    .append(',')
                    .append(r[2])
                    .append(',')
                    .append('"')
                    .append(r[3].replace("\"", "\"\""))
                    .append('"')
                    .append(",,\n");
            key.append(id).append(',').append(r[0]).append(',').append(r[4]).append('\n');
        }
        java.nio.file.Files.writeString(out.resolve("이상치라벨.csv"), sheet);
        java.nio.file.Files.writeString(out.resolve("_key.csv"), key);
        System.out.printf(
                "라벨링 시트 %d건 → %s (후보 풀: %s)%n",
                picked.size(),
                out,
                pool.entrySet().stream()
                        .map(en -> en.getKey() + " " + en.getValue().size())
                        .collect(Collectors.joining(", ")));
    }

    /** 대안: 최근 2주(w-1, w)를 바로 앞 4주(w-5..w-2)와 비교. */
    private boolean risingContiguous(double[] weekly, int w, long budgetWon) {
        if (w < 5) return false;
        double recent = (weekly[w - 1] + weekly[w]) / 2.0;
        double prior = (weekly[w - 5] + weekly[w - 4] + weekly[w - 3] + weekly[w - 2]) / 4.0;
        double frac = budgetWon * props.getTrendFrac();
        if (prior <= 0) return recent > 0 && recent >= frac;
        return recent >= prior * props.getTrendRatio() && (recent - prior) >= frac;
    }

    private long monthSum(LocalDate from, LocalDate to, Boolean variable) {
        return ALL_EXPENSES.stream()
                .filter(e -> e.isExpense()
                        && (variable == null || e.isVariable() == variable)
                        && !e.getDate().isBefore(from)
                        && !e.getDate().isAfter(to))
                .mapToLong(Expense::getAmount)
                .sum();
    }

    private static double pctError(double predicted, long actual) {
        return Math.abs(predicted - actual) / (double) actual * 100;
    }

    // ──────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────

    /** 해당 주의 월 예산을 반환. budgets.csv에 없는 달은 균등 배분으로 대체. */
    private Map<Integer, Long> budgetForMonth(LocalDate weekEnd) {
        return BUDGETS_BY_MONTH.getOrDefault(YearMonth.from(weekEnd), defaultBudget());
    }

    private Map<Integer, Long> defaultBudget() {
        long each = AVAILABLE_BUDGET / 11;
        Map<Integer, Long> m = new HashMap<>();
        for (int i = 1; i <= 11; i++) m.put(i, each);
        return m;
    }

    private List<Expense> expensesUpTo(LocalDate to) {
        return ALL_EXPENSES.stream().filter(e -> !e.getDate().isAfter(to)).collect(Collectors.toList());
    }

    private List<Expense> expensesInRange(List<Expense> pool, LocalDate from, LocalDate to) {
        return pool.stream()
                .filter(e -> !e.getDate().isBefore(from) && !e.getDate().isAfter(to))
                .collect(Collectors.toList());
    }

    private boolean checkLowData(int currentWeek, int thisWeekCount) {
        List<Long> pastCounts = new ArrayList<>();
        for (int w = 1; w < currentWeek; w++) {
            LocalDate wEnd = FIRST_MONDAY.plusWeeks(w).minusDays(1);
            LocalDate wStart = wEnd.minusDays(6);
            long cnt = ALL_EXPENSES.stream()
                    .filter(e -> !e.getDate().isBefore(wStart) && !e.getDate().isAfter(wEnd))
                    .count();
            pastCounts.add(cnt);
        }
        double normal = activityGuard.normalWeeklyCount(pastCounts);
        return activityGuard.isLowData(thisWeekCount, normal);
    }

    private boolean detectHighAnomaly(
            List<Expense> history, List<Expense> thisWeek, LocalDate weekStart, Map<Integer, Long> catBudget) {
        for (int catId : catBudget.keySet()) {
            if (anomalyDetector.isSpikeProne(catId)) continue;
            long budget = catBudget.getOrDefault(catId, 100_000L);

            double[] catHistory = history.stream()
                    .filter(e -> e.isExpense()
                            && Objects.equals(e.getCategoryId(), catId)
                            && e.getDate().isBefore(weekStart)
                            && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                    .mapToDouble(Expense::getAmount)
                    .toArray();

            for (Expense e : thisWeek) {
                if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), catHistory, budget);
                if (z.isPresent() && Confidence.of(z.getAsDouble()) == Confidence.HIGH) return true;
            }
        }
        return false;
    }

    private long calcSavingsImpact(List<Expense> history, LocalDate weekEnd) {
        LocalDate monthStart = weekEnd.withDayOfMonth(1);
        int elapsedDays = weekEnd.getDayOfMonth();
        int daysInMonth = weekEnd.lengthOfMonth();

        long variableMtd = history.stream()
                .filter(e -> e.isExpense()
                        && e.isVariable()
                        && !e.getDate().isBefore(monthStart)
                        && !e.getDate().isAfter(weekEnd))
                .mapToLong(Expense::getAmount)
                .sum();

        long fixedMtd = history.stream()
                .filter(e -> e.isExpense()
                        && !e.isVariable()
                        && !e.getDate().isBefore(monthStart)
                        && !e.getDate().isAfter(weekEnd))
                .mapToLong(Expense::getAmount)
                .sum();

        LocalDate threeMonthsAgo = monthStart.minusMonths(3);
        double priorDailyRate = history.stream()
                .filter(e -> e.isExpense()
                        && e.isVariable()
                        && !e.getDate().isBefore(threeMonthsAgo)
                        && e.getDate().isBefore(monthStart))
                .mapToLong(Expense::getAmount)
                .average()
                .orElse(0.0);

        BudgetProjector.Projection proj =
                budgetProjector.project(variableMtd, fixedMtd, 0, elapsedDays, daysInMonth, priorDailyRate, null, null);

        return budgetProjector.savingsImpact(proj.predictedMonthEndWon(), AVAILABLE_BUDGET);
    }

    private boolean hasOverBudgetCategory(List<Expense> history, LocalDate weekEnd, Map<Integer, Long> catBudget) {
        LocalDate monthStart = weekEnd.withDayOfMonth(1);
        for (Map.Entry<Integer, Long> entry : catBudget.entrySet()) {
            long catSpend = history.stream()
                    .filter(e -> e.isExpense()
                            && Objects.equals(e.getCategoryId(), entry.getKey())
                            && !e.getDate().isBefore(monthStart)
                            && !e.getDate().isAfter(weekEnd))
                    .mapToLong(Expense::getAmount)
                    .sum();
            if (catSpend > entry.getValue()) return true;
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────
    // CSV 로더
    // ──────────────────────────────────────────────────────────────

    private static List<Expense> loadTransactions() throws Exception {
        List<Expense> result = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                WalkForwardValidationTest.class.getResourceAsStream("/testdata/transactions.csv"),
                "transactions.csv 파일을 찾을 수 없습니다.")))) {
            br.readLine();
            String line;
            long id = 1L;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length < 2 || cols[1].isBlank()) continue;
                try {
                    LocalDate date = LocalDate.parse(cols[0].trim(), DATE_FMT);
                    if (date.isBefore(DATA_START)) continue;
                    long amount = Long.parseLong(cols[1].trim());
                    int categoryId = Integer.parseInt(cols[2].trim());
                    String emotion = cols[3].trim();
                    boolean isRecurring = "TRUE".equalsIgnoreCase(cols[4].trim());
                    String memo = cols.length > 5 ? cols[5].trim() : "";

                    result.add(Expense.builder()
                            .id(id++)
                            .userId(USER_ID)
                            .categoryId(categoryId)
                            .type("EXPENSE")
                            .amount(amount)
                            .expenseDate(date.atStartOfDay())
                            .memo(memo)
                            .emotion(emotion)
                            .recurring(isRecurring)
                            .planned(false)
                            .build());
                } catch (Exception e) {
                    skipped++;
                }
            }
        }
        if (skipped > 0) System.out.printf("[transactions.csv] 파싱 실패 %d행 스킵%n", skipped);
        if (result.isEmpty()) throw new IllegalStateException("transactions.csv 파싱 결과가 비어 있습니다. CSV 포맷을 확인하세요.");
        return result;
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 4] LLM 피드백 렌더링 — 모든 ALERT 주차 실거래 기반
    // ──────────────────────────────────────────────────────────────
    @Test
    @Tag("llm")
    @DisplayName("[검증 4] FeedbackRenderer: 전체 ALERT 주차 LLM 피드백 생성")
    void llmFeedback_render() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY 미설정 → 스킵");

        // AnthropicLlmClient 직접 생성 (리플렉션으로 @Value 필드 주입)
        AnthropicLlmClient rawLlmClient = new AnthropicLlmClient();
        setField(rawLlmClient, "apiKey", apiKey);
        setField(rawLlmClient, "model", "claude-sonnet-4-5");
        setField(rawLlmClient, "maxTokens", 600);
        // 호출을 기록해 1차 통과 / 재시도 후 통과 / 템플릿 폴백을 집계한다 (#111)
        LlmRenderMeter stats = new LlmRenderMeter();
        LlmClient llmClient = stats.wrap(rawLlmClient);

        // ObjectProvider — getIfAvailable()만 구현
        ObjectProvider<LlmClient> provider = new ObjectProvider<>() {
            public LlmClient getObject() {
                return llmClient;
            }

            public LlmClient getObject(Object... args) {
                return llmClient;
            }

            public LlmClient getIfAvailable() {
                return llmClient;
            }

            public LlmClient getIfUnique() {
                return llmClient;
            }
        };
        FeedbackRenderer renderer = new FeedbackRenderer(provider);

        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        int renderedCount = 0;

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;

            Map<Integer, Long> catBudget = budgetForMonth(weekEnd);
            List<Expense> history = expensesUpTo(weekEnd);
            List<Expense> thisWeek = expensesInRange(history, weekStart, weekEnd);

            boolean isLowData = checkLowData(week, thisWeek.size());
            boolean hasHighAnomaly = !isLowData && detectHighAnomaly(history, thisWeek, weekStart, catBudget);
            long savingsImpact = calcSavingsImpact(history, weekEnd);
            boolean hasOverBudget = hasOverBudgetCategory(history, weekEnd, catBudget);

            FeedbackType type = feedbackTypeResolver.resolve(isLowData, savingsImpact, hasHighAnomaly, hasOverBudget);
            if (type != FeedbackType.ALERT) continue;
            if (!LLM_WEEKS.isEmpty() && !LLM_WEEKS.contains(week)) continue;

            // ── overspendCategories (CauseSignalAssembler로 메모·감정 포함) ──
            LocalDate monthStart = weekEnd.withDayOfMonth(1);
            List<FeedbackRenderer.CategoryOverspend> overspend = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : catBudget.entrySet()) {
                long catSpend = history.stream()
                        .filter(e -> e.isExpense()
                                && Objects.equals(e.getCategoryId(), entry.getKey())
                                && !e.getDate().isBefore(monthStart)
                                && !e.getDate().isAfter(weekEnd))
                        .mapToLong(Expense::getAmount)
                        .sum();
                if (catSpend > entry.getValue()) {
                    CauseSignals cs = causeSignalAssembler.assemble(entry.getKey(), history, weekStart, weekEnd, null);
                    long thisWeekAmt = history.stream()
                            .filter(e -> e.isExpense()
                                    && Objects.equals(e.getCategoryId(), entry.getKey())
                                    && !e.getDate().isBefore(weekStart)
                                    && !e.getDate().isAfter(weekEnd))
                            .mapToLong(Expense::getAmount)
                            .sum();
                    int thisWeekCnt = (int) thisWeek.stream()
                            .filter(e -> e.isVariable() && Objects.equals(e.getCategoryId(), entry.getKey()))
                            .count();
                    List<String> weekMemos = thisWeek.stream()
                            .filter(e -> e.isVariable() && Objects.equals(e.getCategoryId(), entry.getKey()))
                            .map(Expense::getMemo)
                            .toList();
                    overspend.add(new FeedbackRenderer.CategoryOverspend(
                            Categories.name(entry.getKey()),
                            catSpend - entry.getValue(),
                            thisWeekAmt,
                            cs,
                            thisWeekCnt,
                            MerchantNames.countByMerchant(weekMemos)));
                }
            }

            // ── anomaly (신뢰도 높은 것 우선, 동급이면 magnitude 큰 것) ──
            String anomalyCategory = null;
            int anomalyCatId = -1;
            double anomalyMag = 0;
            Confidence anomalyConf = null;
            String anomalyEmotion = null;
            FeedbackRenderer.AnomalyBasis anomalyBasis = null;
            for (int catId : catBudget.keySet()) {
                if (anomalyDetector.isSpikeProne(catId)) continue;
                long budget = catBudget.getOrDefault(catId, 100_000L);
                double[] catHist = history.stream()
                        .filter(e -> e.isExpense()
                                && Objects.equals(e.getCategoryId(), catId)
                                && e.getDate().isBefore(weekStart)
                                && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                        .mapToDouble(Expense::getAmount)
                        .toArray();
                for (Expense e : thisWeek) {
                    if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                    OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), catHist, budget);
                    if (z.isEmpty()) continue;
                    Confidence conf = Confidence.of(
                            z.getAsDouble(),
                            catHist.length,
                            props.getConfHighMinSamples(),
                            props.getConfMediumMinSamples());
                    double mag = catHist.length > 0
                            ? e.getAmount() / Math.max(RobustStats.median(catHist), 1)
                            : z.getAsDouble();
                    if (anomalyConf == null
                            || conf.ordinal() < anomalyConf.ordinal()
                            || (conf == anomalyConf && mag > anomalyMag)) {
                        anomalyCatId = catId;
                        anomalyCategory = Categories.name(catId);
                        anomalyMag = mag;
                        anomalyConf = conf;
                        anomalyEmotion = e.getEmotion();
                        anomalyBasis = pointBasis(e, catHist);
                    }
                }
            }
            final int finalAnomalyCatId = anomalyCatId;
            long anomalyWeeklyAmount = anomalyCatId >= 0
                    ? thisWeek.stream()
                            .filter(e -> e.isVariable() && Objects.equals(e.getCategoryId(), finalAnomalyCatId))
                            .mapToLong(Expense::getAmount)
                            .sum()
                    : 0L;
            int anomalyWeeklyCount = anomalyCatId >= 0
                    ? (int) thisWeek.stream()
                            .filter(e -> e.isVariable() && Objects.equals(e.getCategoryId(), finalAnomalyCatId))
                            .count()
                    : 0;

            // ── 추가 HIGH 이상치 (primary 카테고리 제외, HIGH confidence, 최대 2개) ──
            List<FeedbackRenderer.AnomalyInfo> additionalHighAnomalies = new ArrayList<>();
            for (int catId : catBudget.keySet()) {
                if (catId == anomalyCatId) continue; // primary 제외
                if (anomalyDetector.isSpikeProne(catId)) continue;
                double[] catHist = history.stream()
                        .filter(e -> e.isExpense()
                                && Objects.equals(e.getCategoryId(), catId)
                                && e.getDate().isBefore(weekStart)
                                && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                        .mapToDouble(Expense::getAmount)
                        .toArray();
                double bestMag = 0;
                String bestEmotion = null;
                FeedbackRenderer.AnomalyBasis bestBasis = null;
                for (Expense e : thisWeek) {
                    if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                    OptionalDouble z =
                            anomalyDetector.checkPoint(e.getAmount(), catHist, catBudget.getOrDefault(catId, 100_000L));
                    if (z.isEmpty()) continue;
                    Confidence conf = Confidence.of(
                            z.getAsDouble(),
                            catHist.length,
                            props.getConfHighMinSamples(),
                            props.getConfMediumMinSamples());
                    if (conf != Confidence.HIGH) continue;
                    double mag = catHist.length > 0
                            ? e.getAmount() / Math.max(RobustStats.median(catHist), 1)
                            : z.getAsDouble();
                    if (mag > bestMag) {
                        bestMag = mag;
                        bestEmotion = e.getEmotion();
                        bestBasis = pointBasis(e, catHist);
                    }
                }
                if (bestMag > 0) {
                    final int fCatId = catId;
                    long catWeeklyAmt = thisWeek.stream()
                            .filter(e -> e.isVariable() && Objects.equals(e.getCategoryId(), fCatId))
                            .mapToLong(Expense::getAmount)
                            .sum();
                    additionalHighAnomalies.add(new FeedbackRenderer.AnomalyInfo(
                            Categories.name(catId), bestMag, catWeeklyAmt, bestEmotion, bestBasis, (int)
                                    thisWeek.stream()
                                            .filter(e -> e.isVariable() && Objects.equals(e.getCategoryId(), fCatId))
                                            .count()));
                }
            }
            additionalHighAnomalies.sort(Comparator.comparingDouble(FeedbackRenderer.AnomalyInfo::magnitude)
                    .reversed());
            if (additionalHighAnomalies.size() > 2) {
                additionalHighAnomalies = new ArrayList<>(additionalHighAnomalies.subList(0, 2));
            }

            // ── BudgetProjector ──
            int elapsed = weekEnd.getDayOfMonth();
            int daysInMonth = weekEnd.lengthOfMonth();
            long varMtd = history.stream()
                    .filter(e -> e.isExpense()
                            && e.isVariable()
                            && !e.getDate().isBefore(monthStart)
                            && !e.getDate().isAfter(weekEnd))
                    .mapToLong(Expense::getAmount)
                    .sum();
            long fixedMtd = history.stream()
                    .filter(e -> e.isExpense()
                            && !e.isVariable()
                            && !e.getDate().isBefore(monthStart)
                            && !e.getDate().isAfter(weekEnd))
                    .mapToLong(Expense::getAmount)
                    .sum();
            double priorDailyRate = history.stream()
                    .filter(e -> e.isExpense()
                            && e.isVariable()
                            && !e.getDate().isBefore(monthStart.minusMonths(3))
                            && e.getDate().isBefore(monthStart))
                    .mapToLong(Expense::getAmount)
                    .average()
                    .orElse(0.0);
            BudgetProjector.Projection proj =
                    budgetProjector.project(varMtd, fixedMtd, 0, elapsed, daysInMonth, priorDailyRate, null, null);

            BandWidth bw = (weekStart.getMonth() != weekEnd.getMonth())
                    ? null
                    : elapsed <= 10 ? BandWidth.WIDE : elapsed <= 20 ? BandWidth.MEDIUM : BandWidth.NARROW;
            Confidence overall = anomalyConf != null ? anomalyConf : Confidence.MEDIUM;

            // ── SavingsActionCalculator로 실제 절약 액션 계산 ──
            Map<Integer, Long> overspendMap = new HashMap<>();
            Map<Integer, CauseSignals> causeSignalsByCategory = new HashMap<>();
            for (FeedbackRenderer.CategoryOverspend ov : overspend) {
                // categoryId를 역조회 (name → id)
                catBudget.keySet().stream()
                        .filter(id -> Categories.name(id).equals(ov.categoryName()))
                        .findFirst()
                        .ifPresent(id -> {
                            overspendMap.put(id, ov.overAmountWon());
                            if (ov.causeSignals() != null) causeSignalsByCategory.put(id, ov.causeSignals());
                        });
            }
            // 운영 경로와 같은 입력: 카테고리별 남은 기간 예상 변동 지출 (#122 — 계산기 인자 의미가 이번 달 누적에서 바뀜)
            LocalDate prevMonthStart = monthStart.minusMonths(1);
            Map<Integer, Long> categoryRemainingExpected = new HashMap<>();
            for (int catId : catBudget.keySet()) {
                long varMtdCat = history.stream()
                        .filter(e -> e.isExpense()
                                && e.isVariable()
                                && Objects.equals(e.getCategoryId(), catId)
                                && !e.getDate().isBefore(monthStart)
                                && !e.getDate().isAfter(weekEnd))
                        .mapToLong(Expense::getAmount)
                        .sum();
                long priorSum = history.stream()
                        .filter(e -> e.isVariable()
                                && Objects.equals(e.getCategoryId(), catId)
                                && !e.getDate().isBefore(prevMonthStart)
                                && e.getDate().isBefore(monthStart))
                        .mapToLong(Expense::getAmount)
                        .sum();
                double priorCatRate = priorSum > 0 ? (double) priorSum / prevMonthStart.lengthOfMonth() : 0.0;
                categoryRemainingExpected.put(
                        catId,
                        BudgetProjector.remainingCategoryExpected(
                                varMtdCat, elapsed, daysInMonth, priorCatRate, null, null));
            }
            List<Expense> thisMonthExpenses = history.stream()
                    .filter(e -> e.isExpense()
                            && !e.getDate().isBefore(monthStart)
                            && !e.getDate().isAfter(weekEnd))
                    .toList();
            long savingsGap = Math.max(-savingsImpact, 0);
            List<ActionPlan> plans = savingsActionCalculator.calculate(
                    overspendMap,
                    savingsGap,
                    elapsed,
                    daysInMonth - elapsed,
                    categoryRemainingExpected,
                    causeSignalsByCategory,
                    thisMonthExpenses,
                    List.of(),
                    List.of());
            List<FeedbackRenderer.ActionSummary> actions = plans.stream()
                    .map(p -> new FeedbackRenderer.ActionSummary(
                            Categories.name(p.categoryId()), p.reductionWon(),
                            p.dominantFactor(), p.feasibilityScore()))
                    .toList();

            // ── 주목 거래 수집: 이상치 OR REGRET/IMPULSE OR 메모 ──
            Map<Integer, List<Expense>> byCatW = new HashMap<>();
            for (Expense e : history) {
                if (!e.isVariable()) continue;
                byCatW.computeIfAbsent(e.getCategoryId(), k -> new ArrayList<>())
                        .add(e);
            }
            byCatW.values().forEach(list -> list.sort(Comparator.comparing(Expense::getDate)));

            List<FeedbackRenderer.TransactionHighlight> highlights = new ArrayList<>();
            for (Expense e : thisWeek) {
                if (!e.isVariable()) continue;
                LocalDate d = e.getDate();
                int catId = e.getCategoryId();
                boolean isAnomaly = false;
                if (!anomalyDetector.isSpikeProne(catId)) {
                    long budget = catBudget.getOrDefault(catId, 100_000L);
                    double[] hist = byCatW.getOrDefault(catId, List.of()).stream()
                            .filter(x -> x.getDate().isBefore(d)
                                    && ChronoUnit.DAYS.between(x.getDate(), d) <= props.getBaselineWeeks() * 7L
                                    && (!props.isBimodal(catId) || x.getAmount() >= props.getBimodalMin()))
                            .mapToDouble(Expense::getAmount)
                            .toArray();
                    isAnomaly = anomalyDetector
                            .checkPoint(e.getAmount(), hist, budget)
                            .isPresent();
                }
                boolean highlightEmo = e.getEmotion() != null
                        && (e.getEmotion().equalsIgnoreCase("REGRET")
                                || e.getEmotion().equalsIgnoreCase("IMPULSE"));
                boolean hasMemo = e.getMemo() != null && !e.getMemo().isBlank();
                if (isAnomaly || highlightEmo || hasMemo) {
                    highlights.add(new FeedbackRenderer.TransactionHighlight(
                            d, Categories.name(catId), e.getAmount(), e.getMemo(), e.getEmotion()));
                }
            }
            List<FeedbackRenderer.TransactionHighlight> topHighlights =
                    FeedbackRenderer.TransactionHighlight.groupIdenticalTopN(highlights, 5);

            // 이번 주 전체 변동지출 감정 분포
            Map<String, Long> wkEmoCounts = new java.util.LinkedHashMap<>();
            Map<String, Long> wkEmoAmounts = new java.util.LinkedHashMap<>();
            long wkUntaggedWon = 0L;
            for (Expense e : thisWeek) {
                if (!e.isExpense() || !e.isVariable()) continue;
                String emo = e.getEmotion();
                if (emo != null && !emo.isBlank()) {
                    wkEmoCounts.merge(emo, 1L, Long::sum);
                    wkEmoAmounts.merge(emo, e.getAmount(), Long::sum);
                } else {
                    wkUntaggedWon += e.getAmount();
                }
            }
            FeedbackRenderer.WeekEmotionSummary weekEmo = wkEmoCounts.isEmpty()
                    ? null
                    : new FeedbackRenderer.WeekEmotionSummary(wkEmoCounts, wkEmoAmounts, wkUntaggedWon);

            // 이번 주 총 변동지출
            long weekTotalWon = thisWeek.stream()
                    .filter(e -> e.isExpense() && e.isVariable())
                    .mapToLong(Expense::getAmount)
                    .sum();

            // 전주 총 변동지출
            LocalDate prevWeekStart = weekStart.minusWeeks(1);
            LocalDate prevWeekEnd = weekEnd.minusWeeks(1);
            List<Expense> prevHistory = expensesUpTo(prevWeekEnd);
            long prevWeekTotalWon = expensesInRange(prevHistory, prevWeekStart, prevWeekEnd).stream()
                    .filter(e -> e.isExpense() && e.isVariable())
                    .mapToLong(Expense::getAmount)
                    .sum();

            FeedbackRenderer.FeedbackContext ctx = new FeedbackRenderer.FeedbackContext(
                    proj.predictedMonthEndWon(),
                    AVAILABLE_BUDGET,
                    savingsImpact,
                    (double) varMtd / AVAILABLE_BUDGET,
                    null,
                    anomalyCategory != null,
                    anomalyCategory,
                    anomalyMag,
                    anomalyWeeklyAmount,
                    anomalyConf != null ? anomalyConf : Confidence.LOW,
                    anomalyEmotion,
                    additionalHighAnomalies,
                    weekEmo,
                    weekTotalWon,
                    prevWeekTotalWon,
                    0.0,
                    overspend,
                    actions,
                    topHighlights,
                    type,
                    bw,
                    overall,
                    anomalyBasis,
                    anomalyWeeklyCount,
                    weekEnd.lengthOfMonth() - weekEnd.getDayOfMonth());

            System.out.printf("%n=== [검증 4] 제%d주차 (%s ~ %s) ===%n", week, weekStart, weekEnd);
            System.out.printf("예측 월말 지출  : %,d원 / 가용예산 %,d원%n", ctx.predictedMonthEndWon(), AVAILABLE_BUDGET);
            System.out.printf("savingsImpact   : %,d원%n", ctx.savingsImpactWon());
            System.out.printf(
                    "이상 탐지       : %s%n",
                    ctx.hasAnomaly()
                            ? ctx.anomalyCategoryName() + " (" + String.format("%.1f", ctx.anomalyMagnitude()) + "배, "
                                    + ctx.anomalyConfidence() + ")"
                            : "없음");
            if (!ctx.additionalHighAnomalies().isEmpty()) {
                for (FeedbackRenderer.AnomalyInfo a : ctx.additionalHighAnomalies()) {
                    System.out.printf("추가 HIGH 이상치: %s (%.1f배)%n", a.categoryName(), a.magnitude());
                }
            }
            if (!overspend.isEmpty()) {
                System.out.print("예산 초과 카테고리: ");
                overspend.forEach(o -> System.out.printf("%s +%,d원  ", o.categoryName(), o.overAmountWon()));
                System.out.println();
            }
            System.out.println("-".repeat(60));

            FeedbackRenderer.Rendered result = null;
            for (int r = 0; r < LLM_REPEAT; r++) {
                stats.begin(LlmRenderMeter.legacyRule(renderer, ctx));
                result = renderer.render(ctx);
                stats.end(result.text(), "제" + week + "주차", r + 1);
            }
            System.out.println("신뢰도 : " + result.confidence());
            System.out.println();
            System.out.println(result.text());
            renderedCount++;
        }

        System.out.printf("%n총 %d개 ALERT 주차 피드백 생성 완료%n", renderedCount);
        stats.print("[검증 4] 주간 피드백 (반복 " + LLM_REPEAT + "회)");
        assertThat(renderedCount).isGreaterThanOrEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 5] 월말 결산 LLM 피드백
    // ──────────────────────────────────────────────────────────────
    @Test
    @Tag("llm")
    @DisplayName("[검증 5] FeedbackRenderer: 월별 월말 결산 피드백 생성")
    void monthEndFeedback_render() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY 미설정 → 스킵");

        AnthropicLlmClient rawLlmClient = new AnthropicLlmClient();
        setField(rawLlmClient, "apiKey", apiKey);
        setField(rawLlmClient, "model", "claude-sonnet-4-5");
        setField(rawLlmClient, "maxTokens", 600);
        // 호출을 기록해 1차 통과 / 재시도 후 통과 / 템플릿 폴백을 집계한다 (#111)
        LlmRenderMeter stats = new LlmRenderMeter();
        LlmClient llmClient = stats.wrap(rawLlmClient);

        ObjectProvider<LlmClient> provider = new ObjectProvider<>() {
            public LlmClient getObject() {
                return llmClient;
            }

            public LlmClient getObject(Object... args) {
                return llmClient;
            }

            public LlmClient getIfAvailable() {
                return llmClient;
            }

            public LlmClient getIfUnique() {
                return llmClient;
            }
        };
        FeedbackRenderer renderer = new FeedbackRenderer(provider);

        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        int renderedCount = 0;

        for (Map.Entry<YearMonth, Map<Integer, Long>> entry : BUDGETS_BY_MONTH.entrySet()) {
            YearMonth ym = entry.getKey();
            Map<Integer, Long> catBudget = entry.getValue();
            LocalDate monthStart = ym.atDay(1);
            LocalDate monthEnd = ym.atEndOfMonth();

            // 해당 월이 데이터에 완전히 포함되어 있어야 결산 가능
            if (monthEnd.isAfter(dataEnd)) continue;

            List<Expense> monthExpenses = ALL_EXPENSES.stream()
                    .filter(e ->
                            !e.getDate().isBefore(monthStart) && !e.getDate().isAfter(monthEnd))
                    .toList();

            long actualTotal = monthExpenses.stream()
                    .filter(Expense::isExpense)
                    .mapToLong(Expense::getAmount)
                    .sum();
            if (actualTotal == 0) continue;

            long budgetDiff = AVAILABLE_BUDGET - actualTotal; // 양수=절약, 음수=초과

            // 카테고리별 실적 vs 예산
            Map<Integer, Long> catActual = new HashMap<>();
            for (Expense e : monthExpenses) {
                if (!e.isExpense()) continue;
                catActual.merge(e.getCategoryId(), e.getAmount(), Long::sum);
            }

            List<FeedbackRenderer.CategoryResult> overspend = new ArrayList<>();
            List<FeedbackRenderer.CategoryResult> savings = new ArrayList<>();
            for (Map.Entry<Integer, Long> catEntry : catBudget.entrySet()) {
                int catId = catEntry.getKey();
                long budget = catEntry.getValue();
                long actual = catActual.getOrDefault(catId, 0L);
                long diff = actual - budget;
                if (diff > 0) {
                    overspend.add(new FeedbackRenderer.CategoryResult(Categories.name(catId), diff));
                } else if (diff < 0 && actual > 0) {
                    savings.add(new FeedbackRenderer.CategoryResult(Categories.name(catId), -diff));
                }
            }
            overspend.sort(Comparator.comparingLong(FeedbackRenderer.CategoryResult::amountWon)
                    .reversed());
            savings.sort(Comparator.comparingLong(FeedbackRenderer.CategoryResult::amountWon)
                    .reversed());

            FeedbackRenderer.MonthEndContext ctx = new FeedbackRenderer.MonthEndContext(
                    ym, actualTotal, AVAILABLE_BUDGET, budgetDiff, overspend, savings);

            System.out.printf("%n=== [검증 5] %s 월말 결산 ===%n", ym);
            System.out.printf("실제 총지출  : %,d원 / 가용예산 %,d원%n", actualTotal, AVAILABLE_BUDGET);
            System.out.printf("예산 대비    : %s %,d원%n", budgetDiff >= 0 ? "절약" : "초과", Math.abs(budgetDiff));
            if (!overspend.isEmpty()) {
                System.out.print("초과 카테고리: ");
                overspend.forEach(o -> System.out.printf("%s +%,d원  ", o.categoryName(), o.amountWon()));
                System.out.println();
            }
            if (!savings.isEmpty()) {
                System.out.print("절약 카테고리: ");
                savings.forEach(s -> System.out.printf("%s -%,d원  ", s.categoryName(), s.amountWon()));
                System.out.println();
            }
            System.out.println("-".repeat(60));

            FeedbackRenderer.Rendered result = null;
            for (int r = 0; r < LLM_REPEAT; r++) {
                stats.begin(LlmRenderMeter.legacyRule(renderer, ctx));
                result = renderer.renderMonthEnd(ctx);
                stats.end(result.text(), ym + " 월말", r + 1);
            }
            System.out.println("신뢰도 : " + result.confidence());
            System.out.println();
            System.out.println(result.text());
            renderedCount++;
        }

        System.out.printf("%n총 %d개월 결산 피드백 생성 완료%n", renderedCount);
        stats.print("[검증 5] 월말 결산 (반복 " + LLM_REPEAT + "회)");
        assertThat(renderedCount).isGreaterThanOrEqualTo(0);
    }

    /** 리플렉션으로 private @Value 필드에 값을 주입한다 (테스트 전용). */
    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    /** 단발 이상치의 배수 기준 (#111) — 서비스의 Anomaly.basis()와 같은 규칙. 과거 거래가 없으면 null. */
    private static FeedbackRenderer.AnomalyBasis pointBasis(Expense e, double[] catHist) {
        if (catHist.length == 0) return null;
        long baseline = Math.round(RobustStats.median(catHist));
        if (baseline <= 0) return null;
        String label = e.getMemo() != null && !e.getMemo().isBlank() ? e.getMemo() : e.getTitle();
        return new FeedbackRenderer.AnomalyBasis(true, e.getAmount(), baseline, label);
    }

    /** budgets.csv 로드 → YearMonth → (categoryId → 예산) */
    private static Map<YearMonth, Map<Integer, Long>> loadBudgets() throws Exception {
        Map<YearMonth, Map<Integer, Long>> result = new LinkedHashMap<>();
        int skipped = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                WalkForwardValidationTest.class.getResourceAsStream("/testdata/budgets.csv"),
                "budgets.csv 파일을 찾을 수 없습니다.")))) {
            br.readLine(); // 헤더 스킵 (month,식료품,의류신발,...)
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length < 11 || cols[0].isBlank()) continue;
                try {
                    YearMonth ym = YearMonth.parse(cols[0].trim(), MONTH_FMT);
                    Map<Integer, Long> budget = new HashMap<>();
                    for (int catId = 1; catId <= 10; catId++) {
                        String val = cols[catId].trim();
                        budget.put(catId, val.isEmpty() ? 0L : Long.parseLong(val));
                    }
                    result.put(ym, budget);
                } catch (Exception e) {
                    skipped++;
                }
            }
        }
        if (skipped > 0) System.out.printf("[budgets.csv] 파싱 실패 %d행 스킵%n", skipped);
        if (result.isEmpty()) throw new IllegalStateException("budgets.csv 파싱 결과가 비어 있습니다. CSV 포맷을 확인하세요.");
        return result;
    }
}
