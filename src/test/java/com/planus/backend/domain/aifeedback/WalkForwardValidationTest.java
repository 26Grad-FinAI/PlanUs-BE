package com.planus.backend.domain.aifeedback;

import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.*;
import com.planus.backend.domain.aifeedback.core.RobustStats;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.action.ActionPlan;
import com.planus.backend.domain.aifeedback.action.SavingsActionCalculator;
import com.planus.backend.domain.aifeedback.cause.CauseSignalAssembler;
import com.planus.backend.domain.aifeedback.cause.CauseSignals;
import com.planus.backend.domain.aifeedback.llm.AnthropicLlmClient;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer;
import com.planus.backend.domain.aifeedback.llm.LlmClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static final DateTimeFormatter DATE_FMT   = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter MONTH_FMT  = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final long USER_ID       = 1L;
    private static final int  WARMUP_WEEKS  = 16;
    private static final LocalDate DATA_START = LocalDate.of(2026, 2, 1); // 1월 제외

    private static final long MONTHLY_INCOME   = 1_200_000L; // 월 수입 120만원
    private static final long SAVINGS_GOAL     =   200_000L; // 저축 목표 20만원
    private static final long AVAILABLE_BUDGET = MONTHLY_INCOME - SAVINGS_GOAL; // 가용예산 100만원

    // YearMonth → (categoryId → 예산)
    private static Map<YearMonth, Map<Integer, Long>> BUDGETS_BY_MONTH;

    private static List<Expense> ALL_EXPENSES;
    private static LocalDate FIRST_MONDAY;

    private static AiFeedbackProperties   props;
    private static AnomalyDetector        anomalyDetector;
    private static BudgetProjector        budgetProjector;
    private static PacingComparator       pacingComparator;
    private static ActivityGuard          activityGuard;
    private static FeedbackTypeResolver   feedbackTypeResolver;
    private static CauseSignalAssembler   causeSignalAssembler;
    private static SavingsActionCalculator savingsActionCalculator;

    @BeforeAll
    static void setup() throws Exception {
        ALL_EXPENSES     = loadTransactions();
        BUDGETS_BY_MONTH = loadBudgets();

        assertThat(ALL_EXPENSES).isNotEmpty();
        assertThat(BUDGETS_BY_MONTH).isNotEmpty();

        LocalDate dataStart = ALL_EXPENSES.stream().map(Expense::getDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate dataEnd   = ALL_EXPENSES.stream().map(Expense::getDate).max(Comparator.naturalOrder()).orElseThrow();

        FIRST_MONDAY = dataStart.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        props                = new AiFeedbackProperties();
        anomalyDetector      = new AnomalyDetector(props);
        budgetProjector      = new BudgetProjector();
        pacingComparator     = new PacingComparator();
        activityGuard        = new ActivityGuard(props);
        feedbackTypeResolver = new FeedbackTypeResolver();
        causeSignalAssembler    = new CauseSignalAssembler(new ObjectMapper());
        savingsActionCalculator = new SavingsActionCalculator(props);

        System.out.printf("%n=== 데이터 현황 ===%n");
        System.out.printf("총 거래 건수 : %d건%n", ALL_EXPENSES.size());
        System.out.printf("기간         : %s ~ %s%n", dataStart, dataEnd);
        System.out.printf("월 수입      : %,d원  저축목표: %,d원  가용예산: %,d원%n",
                MONTHLY_INCOME, SAVINGS_GOAL, AVAILABLE_BUDGET);
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
        System.out.printf("%-5s %-22s %-12s %-14s %-8s %-8s%n",
                "주차", "기간", "FeedbackType", "savingsImpact", "이상탐지", "LOW_DATA");
        System.out.println("-".repeat(75));

        int alertCount = 0, positiveCount = 0, lowDataCount = 0;
        LocalDate dataEnd = ALL_EXPENSES.stream().map(Expense::getDate).max(Comparator.naturalOrder()).orElseThrow();

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd   = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;

            Map<Integer, Long> catBudget = budgetForMonth(weekEnd);
            List<Expense> history        = expensesUpTo(weekEnd);
            List<Expense> thisWeek       = expensesInRange(history, weekStart, weekEnd);

            boolean isLowData      = checkLowData(week, thisWeek.size());
            boolean hasHighAnomaly = !isLowData && detectHighAnomaly(history, thisWeek, weekStart, catBudget);
            long savingsImpact     = calcSavingsImpact(history, weekEnd);
            boolean hasOverBudget  = hasOverBudgetCategory(history, weekEnd, catBudget);

            FeedbackType type = feedbackTypeResolver.resolve(isLowData, savingsImpact, hasHighAnomaly, hasOverBudget);

            switch (type) {
                case ALERT    -> alertCount++;
                case POSITIVE -> positiveCount++;
                case LOW_DATA -> lowDataCount++;
            }

            System.out.printf("%-5d %-22s %-12s %-14s %-8s %-8s%n",
                    week,
                    weekStart + " ~ " + weekEnd,
                    type,
                    String.format("%,d원", savingsImpact),
                    hasHighAnomaly ? "YES" : "-",
                    isLowData      ? "YES" : "-");
        }

        System.out.println("-".repeat(75));
        System.out.printf("ALERT: %d건 / POSITIVE: %d건 / LOW_DATA: %d건%n%n",
                alertCount, positiveCount, lowDataCount);

        assertThat(alertCount + positiveCount + lowDataCount).isGreaterThan(0);
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 2] 카테고리별 이상 탐지 건수
    // ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[검증 2] AnomalyDetector: 카테고리별 이상 탐지 현황")
    void anomalyDetector_categoryBreakdown() {
        Map<Integer, Integer> countByCategory = new TreeMap<>();
        LocalDate dataEnd = ALL_EXPENSES.stream().map(Expense::getDate).max(Comparator.naturalOrder()).orElseThrow();

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd   = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;

            Map<Integer, Long> catBudget = budgetForMonth(weekEnd);
            List<Expense> history        = expensesUpTo(weekEnd);
            List<Expense> thisWeek       = expensesInRange(history, weekStart, weekEnd);

            for (int catId : catBudget.keySet()) {
                if (anomalyDetector.isSpikeProne(catId)) continue;
                long budget = catBudget.getOrDefault(catId, 100_000L);

                double[] catHistory = history.stream()
                        .filter(e -> e.isExpense() && Objects.equals(e.getCategoryId(), catId)
                                && e.getDate().isBefore(weekStart)
                                && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                        .mapToDouble(Expense::getAmount).toArray();

                for (Expense e : thisWeek) {
                    if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                    OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), catHistory, budget);
                    if (z.isPresent()) countByCategory.merge(catId, 1, Integer::sum);
                }
            }
        }

        System.out.println("=== [검증 2] 카테고리별 이상 탐지 건수 ===");
        countByCategory.forEach((catId, cnt) ->
                System.out.printf("  [%2d] %-18s : %d건%n", catId, Categories.name(catId), cnt));
        System.out.println();

        assertThat(countByCategory).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 2-1] 이상 탐지 항목 상세
    // ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[검증 2-1] AnomalyDetector: 이상 탐지 항목 상세")
    void anomalyDetector_detail() {
        LocalDate dataEnd = ALL_EXPENSES.stream().map(Expense::getDate).max(Comparator.naturalOrder()).orElseThrow();

        System.out.println("=== [검증 2-1] 이상 탐지 항목 상세 ===");
        System.out.printf("%-12s %-10s %-18s %-8s %-8s %-8s %-20s%n",
                "날짜", "카테고리", "금액", "중앙값", "z-score", "신뢰도", "메모");
        System.out.println("-".repeat(100));

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd   = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;

            Map<Integer, Long> catBudget = budgetForMonth(weekEnd);
            List<Expense> history        = expensesUpTo(weekEnd);
            List<Expense> thisWeek       = expensesInRange(history, weekStart, weekEnd);

            for (int catId : catBudget.keySet()) {
                if (anomalyDetector.isSpikeProne(catId)) continue;
                long budget = catBudget.getOrDefault(catId, 100_000L);

                double[] catHistory = history.stream()
                        .filter(e -> e.isExpense() && Objects.equals(e.getCategoryId(), catId)
                                && e.getDate().isBefore(weekStart)
                                && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                        .mapToDouble(Expense::getAmount).toArray();

                double median = catHistory.length > 0 ? RobustStats.median(catHistory) : 0;

                for (Expense e : thisWeek) {
                    if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                    OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), catHistory, budget);
                    if (z.isEmpty()) continue;

                    Confidence confidence = Confidence.of(z.getAsDouble(), catHistory.length,
                            props.getConfHighMinSamples(), props.getConfMediumMinSamples());

                    System.out.printf("%-12s %-10s %-18s %-8s %-8s %-8s %-20s%n",
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
    // [검증 3] BudgetProjector 예측 오차 (MAPE)
    // ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[검증 3] BudgetProjector: 월말 예측 오차 (MAPE)")
    void budgetProjector_mape() {
        System.out.println("=== [검증 3] BudgetProjector 예측 오차 (주차별 시점 → 월말 실제 비교) ===");
        System.out.printf("%-6s %-22s %-14s %-14s %-8s%n", "주차", "기간", "예측(원)", "실제(원)", "오차(%)");
        System.out.println("-".repeat(70));

        LocalDate dataEnd = ALL_EXPENSES.stream()
                .map(Expense::getDate).max(Comparator.naturalOrder()).orElseThrow();

        double totalMape = 0;
        int count = 0;

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd   = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;

            LocalDate monthStart = weekEnd.withDayOfMonth(1);
            LocalDate monthEnd   = weekEnd.withDayOfMonth(weekEnd.lengthOfMonth());
            // 월말 실제값 확인 가능한 달만 검증
            if (monthEnd.isAfter(dataEnd)) continue;

            int daysElapsed  = (int) java.time.temporal.ChronoUnit.DAYS.between(monthStart, weekEnd) + 1;
            int daysInMonth  = weekEnd.lengthOfMonth();

            long variableMtd = ALL_EXPENSES.stream()
                    .filter(e -> e.isExpense() && e.isVariable()
                            && !e.getDate().isBefore(monthStart)
                            && !e.getDate().isAfter(weekEnd))
                    .mapToLong(Expense::getAmount).sum();

            long fixedMtd = ALL_EXPENSES.stream()
                    .filter(e -> e.isExpense() && !e.isVariable()
                            && !e.getDate().isBefore(monthStart)
                            && !e.getDate().isAfter(weekEnd))
                    .mapToLong(Expense::getAmount).sum();

            LocalDate threeMonthsAgo = monthStart.minusMonths(3);
            double priorDailyRate = ALL_EXPENSES.stream()
                    .filter(e -> e.isExpense() && e.isVariable()
                            && !e.getDate().isBefore(threeMonthsAgo)
                            && e.getDate().isBefore(monthStart))
                    .mapToLong(Expense::getAmount).average().orElse(0.0);

            BudgetProjector.Projection proj = budgetProjector.project(
                    variableMtd, fixedMtd, 0, daysElapsed, daysInMonth, priorDailyRate, null, null);

            long actual = ALL_EXPENSES.stream()
                    .filter(e -> e.isExpense()
                            && !e.getDate().isBefore(monthStart)
                            && !e.getDate().isAfter(monthEnd))
                    .mapToLong(Expense::getAmount).sum();

            double error = actual > 0
                    ? Math.abs(proj.predictedMonthEndWon() - actual) / (double) actual * 100 : 0.0;
            totalMape += error;
            count++;

            System.out.printf("%-6d %-22s %-14s %-14s %-8.1f%n",
                    week,
                    weekStart + " ~ " + weekEnd,
                    String.format("%,d", proj.predictedMonthEndWon()),
                    String.format("%,d", actual),
                    error);
        }

        double mape = count > 0 ? totalMape / count : 0.0;
        System.out.printf("%-30s %.1f%%%n", "평균 오차 (MAPE) :", mape);
        assertThat(mape).isLessThan(50.0);
    }

    // ──────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────

    /** 해당 주의 월 예산을 반환. budgets.csv에 없는 달은 균등 배분으로 대체. */
    private Map<Integer, Long> budgetForMonth(LocalDate weekEnd) {
        return BUDGETS_BY_MONTH.getOrDefault(
                YearMonth.from(weekEnd),
                defaultBudget());
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
            LocalDate wEnd   = FIRST_MONDAY.plusWeeks(w).minusDays(1);
            LocalDate wStart = wEnd.minusDays(6);
            long cnt = ALL_EXPENSES.stream()
                    .filter(e -> !e.getDate().isBefore(wStart) && !e.getDate().isAfter(wEnd)).count();
            pastCounts.add(cnt);
        }
        double normal = activityGuard.normalWeeklyCount(pastCounts);
        return activityGuard.isLowData(thisWeekCount, normal);
    }

    private boolean detectHighAnomaly(List<Expense> history, List<Expense> thisWeek,
                                      LocalDate weekStart, Map<Integer, Long> catBudget) {
        for (int catId : catBudget.keySet()) {
            if (anomalyDetector.isSpikeProne(catId)) continue;
            long budget = catBudget.getOrDefault(catId, 100_000L);

            double[] catHistory = history.stream()
                    .filter(e -> e.isExpense() && Objects.equals(e.getCategoryId(), catId)
                            && e.getDate().isBefore(weekStart)
                            && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                    .mapToDouble(Expense::getAmount).toArray();

            for (Expense e : thisWeek) {
                if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), catHistory, budget);
                if (z.isPresent() && Confidence.of(z.getAsDouble()) == Confidence.HIGH) return true;
            }
        }
        return false;
    }

    private long calcSavingsImpact(List<Expense> history, LocalDate weekEnd) {
        LocalDate monthStart   = weekEnd.withDayOfMonth(1);
        int elapsedDays        = weekEnd.getDayOfMonth();
        int daysInMonth        = weekEnd.lengthOfMonth();

        long variableMtd = history.stream()
                .filter(e -> e.isExpense() && e.isVariable()
                        && !e.getDate().isBefore(monthStart) && !e.getDate().isAfter(weekEnd))
                .mapToLong(Expense::getAmount).sum();

        long fixedMtd = history.stream()
                .filter(e -> e.isExpense() && !e.isVariable()
                        && !e.getDate().isBefore(monthStart) && !e.getDate().isAfter(weekEnd))
                .mapToLong(Expense::getAmount).sum();

        LocalDate threeMonthsAgo = monthStart.minusMonths(3);
        double priorDailyRate = history.stream()
                .filter(e -> e.isExpense() && e.isVariable()
                        && !e.getDate().isBefore(threeMonthsAgo) && e.getDate().isBefore(monthStart))
                .mapToLong(Expense::getAmount).average().orElse(0.0);

        BudgetProjector.Projection proj = budgetProjector.project(
                variableMtd, fixedMtd, 0, elapsedDays, daysInMonth, priorDailyRate, null, null);

        return budgetProjector.savingsImpact(proj.predictedMonthEndWon(), AVAILABLE_BUDGET);
    }

    private boolean hasOverBudgetCategory(List<Expense> history, LocalDate weekEnd,
                                          Map<Integer, Long> catBudget) {
        LocalDate monthStart = weekEnd.withDayOfMonth(1);
        for (Map.Entry<Integer, Long> entry : catBudget.entrySet()) {
            long catSpend = history.stream()
                    .filter(e -> e.isExpense() && Objects.equals(e.getCategoryId(), entry.getKey())
                            && !e.getDate().isBefore(monthStart) && !e.getDate().isAfter(weekEnd))
                    .mapToLong(Expense::getAmount).sum();
            if (catSpend > entry.getValue()) return true;
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────
    // CSV 로더
    // ──────────────────────────────────────────────────────────────

    private static List<Expense> loadTransactions() throws Exception {
        List<Expense> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(
                        WalkForwardValidationTest.class.getResourceAsStream("/testdata/transactions.csv"),
                        "transactions.csv 파일을 찾을 수 없습니다.")))) {
            br.readLine();
            String line;
            long id = 1L;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length < 2 || cols[1].isBlank()) continue;
                try {
                    LocalDate date      = LocalDate.parse(cols[0].trim(), DATE_FMT);
                    if (date.isBefore(DATA_START)) continue;
                    long amount         = Long.parseLong(cols[1].trim());
                    int categoryId      = Integer.parseInt(cols[2].trim());
                    String emotion      = cols[3].trim();
                    boolean isRecurring = "TRUE".equalsIgnoreCase(cols[4].trim());
                    boolean isPlanned   = "TRUE".equalsIgnoreCase(cols[5].trim());
                    String memo         = cols.length > 6 ? cols[6].trim() : "";

                    result.add(Expense.builder()
                            .id(id++).userId(USER_ID).categoryId(categoryId)
                            .type("EXPENSE").amount(amount)
                            .expenseDate(date.atStartOfDay())
                            .memo(memo).emotion(emotion)
                            .recurring(isRecurring).planned(isPlanned)
                            .build());
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 4] LLM 피드백 렌더링 — 모든 ALERT 주차 실거래 기반
    // ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[검증 4] FeedbackRenderer: 전체 ALERT 주차 LLM 피드백 생성")
    void llmFeedback_render() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY 미설정 → 스킵");

        // AnthropicLlmClient 직접 생성 (리플렉션으로 @Value 필드 주입)
        AnthropicLlmClient llmClient = new AnthropicLlmClient();
        setField(llmClient, "apiKey", apiKey);
        setField(llmClient, "model", "claude-sonnet-4-5");
        setField(llmClient, "maxTokens", 600);

        // ObjectProvider — getIfAvailable()만 구현
        ObjectProvider<LlmClient> provider = new ObjectProvider<>() {
            public LlmClient getObject() { return llmClient; }
            public LlmClient getObject(Object... args) { return llmClient; }
            public LlmClient getIfAvailable() { return llmClient; }
            public LlmClient getIfUnique() { return llmClient; }
        };
        FeedbackRenderer renderer = new FeedbackRenderer(provider);

        LocalDate dataEnd = ALL_EXPENSES.stream().map(Expense::getDate).max(Comparator.naturalOrder()).orElseThrow();
        int renderedCount = 0;

        for (int week = WARMUP_WEEKS + 1; ; week++) {
            LocalDate weekEnd   = FIRST_MONDAY.plusWeeks(week).minusDays(1);
            LocalDate weekStart = weekEnd.minusDays(6);
            if (weekEnd.isAfter(dataEnd)) break;

            Map<Integer, Long> catBudget = budgetForMonth(weekEnd);
            List<Expense> history        = expensesUpTo(weekEnd);
            List<Expense> thisWeek       = expensesInRange(history, weekStart, weekEnd);

            boolean isLowData      = checkLowData(week, thisWeek.size());
            boolean hasHighAnomaly = !isLowData && detectHighAnomaly(history, thisWeek, weekStart, catBudget);
            long savingsImpact     = calcSavingsImpact(history, weekEnd);
            boolean hasOverBudget  = hasOverBudgetCategory(history, weekEnd, catBudget);

            FeedbackType type = feedbackTypeResolver.resolve(isLowData, savingsImpact, hasHighAnomaly, hasOverBudget);
            if (type != FeedbackType.ALERT) continue;

            // ── overspendCategories (CauseSignalAssembler로 메모·감정 포함) ──
            LocalDate monthStart = weekEnd.withDayOfMonth(1);
            List<FeedbackRenderer.CategoryOverspend> overspend = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : catBudget.entrySet()) {
                long catSpend = history.stream()
                        .filter(e -> e.isExpense()
                                && Objects.equals(e.getCategoryId(), entry.getKey())
                                && !e.getDate().isBefore(monthStart)
                                && !e.getDate().isAfter(weekEnd))
                        .mapToLong(Expense::getAmount).sum();
                if (catSpend > entry.getValue()) {
                    CauseSignals cs = causeSignalAssembler.assemble(
                            entry.getKey(), history, weekStart, weekEnd, null);
                    long thisWeekAmt = history.stream()
                            .filter(e -> e.isExpense()
                                    && Objects.equals(e.getCategoryId(), entry.getKey())
                                    && !e.getDate().isBefore(weekStart)
                                    && !e.getDate().isAfter(weekEnd))
                            .mapToLong(Expense::getAmount).sum();
                    overspend.add(new FeedbackRenderer.CategoryOverspend(
                            Categories.name(entry.getKey()), catSpend - entry.getValue(),
                            thisWeekAmt, cs));
                }
            }

            // ── anomaly (신뢰도 높은 것 우선, 동급이면 magnitude 큰 것) ──
            String anomalyCategory = null;
            int anomalyCatId = -1;
            double anomalyMag = 0;
            Confidence anomalyConf = null;
            String anomalyEmotion = null;
            for (int catId : catBudget.keySet()) {
                if (anomalyDetector.isSpikeProne(catId)) continue;
                long budget = catBudget.getOrDefault(catId, 100_000L);
                double[] catHist = history.stream()
                        .filter(e -> e.isExpense() && Objects.equals(e.getCategoryId(), catId)
                                && e.getDate().isBefore(weekStart)
                                && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                        .mapToDouble(Expense::getAmount).toArray();
                for (Expense e : thisWeek) {
                    if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                    OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), catHist, budget);
                    if (z.isEmpty()) continue;
                    Confidence conf = Confidence.of(z.getAsDouble(), catHist.length,
                            props.getConfHighMinSamples(), props.getConfMediumMinSamples());
                    double mag = catHist.length > 0
                            ? e.getAmount() / Math.max(RobustStats.median(catHist), 1) : z.getAsDouble();
                    if (anomalyConf == null || conf.ordinal() < anomalyConf.ordinal()
                            || (conf == anomalyConf && mag > anomalyMag)) {
                        anomalyCatId = catId;
                        anomalyCategory = Categories.name(catId);
                        anomalyMag = mag;
                        anomalyConf = conf;
                        anomalyEmotion = e.getEmotion();
                    }
                }
            }
            final int finalAnomalyCatId = anomalyCatId;
            long anomalyWeeklyAmount = anomalyCatId >= 0
                    ? thisWeek.stream()
                            .filter(e -> e.isExpense() && Objects.equals(e.getCategoryId(), finalAnomalyCatId))
                            .mapToLong(Expense::getAmount).sum()
                    : 0L;

            // ── 2차 이상치 (primary 카테고리 제외) ──
            String secondaryAnomalyCategory = null;
            int secondaryAnomalyCatId = -1;
            double secondaryAnomalyMag = 0;
            Confidence secondaryAnomalyConf = null;
            String secondaryAnomalyEmotionVal = null;
            for (int catId : catBudget.keySet()) {
                if (catId == anomalyCatId) continue; // primary 제외
                if (anomalyDetector.isSpikeProne(catId)) continue;
                double[] catHist = history.stream()
                        .filter(e -> e.isExpense() && Objects.equals(e.getCategoryId(), catId)
                                && e.getDate().isBefore(weekStart)
                                && (!props.isBimodal(catId) || e.getAmount() >= props.getBimodalMin()))
                        .mapToDouble(Expense::getAmount).toArray();
                for (Expense e : thisWeek) {
                    if (!e.isExpense() || !Objects.equals(e.getCategoryId(), catId)) continue;
                    OptionalDouble z = anomalyDetector.checkPoint(e.getAmount(), catHist, catBudget.getOrDefault(catId, 100_000L));
                    if (z.isEmpty()) continue;
                    Confidence conf = Confidence.of(z.getAsDouble(), catHist.length,
                            props.getConfHighMinSamples(), props.getConfMediumMinSamples());
                    double mag = catHist.length > 0
                            ? e.getAmount() / Math.max(RobustStats.median(catHist), 1) : z.getAsDouble();
                    if (secondaryAnomalyConf == null || conf.ordinal() < secondaryAnomalyConf.ordinal()
                            || (conf == secondaryAnomalyConf && mag > secondaryAnomalyMag)) {
                        secondaryAnomalyCatId = catId;
                        secondaryAnomalyCategory = Categories.name(catId);
                        secondaryAnomalyMag = mag;
                        secondaryAnomalyConf = conf;
                        secondaryAnomalyEmotionVal = e.getEmotion();
                    }
                }
            }
            final int finalSecondaryAnomalyCatId = secondaryAnomalyCatId;
            long secondaryAnomalyWeeklyAmount = secondaryAnomalyCatId >= 0
                    ? thisWeek.stream()
                            .filter(e -> e.isExpense() && Objects.equals(e.getCategoryId(), finalSecondaryAnomalyCatId))
                            .mapToLong(Expense::getAmount).sum()
                    : 0L;
            Confidence secondaryConf = secondaryAnomalyConf != null ? secondaryAnomalyConf : Confidence.LOW;

            // ── BudgetProjector ──
            int elapsed = weekEnd.getDayOfMonth();
            int daysInMonth = weekEnd.lengthOfMonth();
            long varMtd = history.stream()
                    .filter(e -> e.isExpense() && e.isVariable()
                            && !e.getDate().isBefore(monthStart) && !e.getDate().isAfter(weekEnd))
                    .mapToLong(Expense::getAmount).sum();
            long fixedMtd = history.stream()
                    .filter(e -> e.isExpense() && !e.isVariable()
                            && !e.getDate().isBefore(monthStart) && !e.getDate().isAfter(weekEnd))
                    .mapToLong(Expense::getAmount).sum();
            double priorDailyRate = history.stream()
                    .filter(e -> e.isExpense() && e.isVariable()
                            && !e.getDate().isBefore(monthStart.minusMonths(3))
                            && e.getDate().isBefore(monthStart))
                    .mapToLong(Expense::getAmount).average().orElse(0.0);
            BudgetProjector.Projection proj = budgetProjector.project(
                    varMtd, fixedMtd, 0, elapsed, daysInMonth, priorDailyRate, null, null);

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
            Map<Integer, Long> categoryMtdSpend = new HashMap<>();
            for (int catId : catBudget.keySet()) {
                long spent = history.stream()
                        .filter(e -> e.isExpense() && Objects.equals(e.getCategoryId(), catId)
                                && !e.getDate().isBefore(monthStart) && !e.getDate().isAfter(weekEnd))
                        .mapToLong(Expense::getAmount).sum();
                categoryMtdSpend.put(catId, spent);
            }
            List<Expense> thisMonthExpenses = history.stream()
                    .filter(e -> e.isExpense()
                            && !e.getDate().isBefore(monthStart) && !e.getDate().isAfter(weekEnd))
                    .toList();
            long savingsGap = Math.max(-savingsImpact, 0);
            List<ActionPlan> plans = savingsActionCalculator.calculate(
                    overspendMap, savingsGap, elapsed, daysInMonth - elapsed,
                    categoryMtdSpend, causeSignalsByCategory,
                    thisMonthExpenses, List.of(), List.of());
            List<FeedbackRenderer.ActionSummary> actions = plans.stream()
                    .map(p -> new FeedbackRenderer.ActionSummary(
                            Categories.name(p.categoryId()), p.reductionWon(),
                            p.dominantFactor(), p.feasibilityScore()))
                    .toList();

            // ── 주목 거래 수집: 이상치 OR REGRET/IMPULSE OR 메모 ──
            Map<Integer, List<Expense>> byCatW = new HashMap<>();
            for (Expense e : history) {
                if (!e.isVariable()) continue;
                byCatW.computeIfAbsent(e.getCategoryId(), k -> new ArrayList<>()).add(e);
            }
            byCatW.values().forEach(list -> list.sort(Comparator.comparing(Expense::getDate)));

            List<FeedbackRenderer.TransactionHighlight> highlights = new ArrayList<>();
            for (Expense e : thisWeek) {
                if (!e.isExpense()) continue;
                LocalDate d = e.getDate();
                int catId = e.getCategoryId();
                boolean isAnomaly = false;
                if (!anomalyDetector.isSpikeProne(catId)) {
                    long budget = catBudget.getOrDefault(catId, 100_000L);
                    double[] hist = byCatW.getOrDefault(catId, List.of()).stream()
                            .filter(x -> x.getDate().isBefore(d)
                                    && (!props.isBimodal(catId) || x.getAmount() >= props.getBimodalMin()))
                            .mapToDouble(Expense::getAmount).toArray();
                    isAnomaly = anomalyDetector.checkPoint(e.getAmount(), hist, budget).isPresent();
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
            List<FeedbackRenderer.TransactionHighlight> topHighlights = highlights.stream()
                    .sorted(Comparator.comparingLong(FeedbackRenderer.TransactionHighlight::amountWon).reversed())
                    .limit(5).toList();

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
            LocalDate prevWeekEnd   = weekEnd.minusWeeks(1);
            List<Expense> prevHistory = expensesUpTo(prevWeekEnd);
            long prevWeekTotalWon = expensesInRange(prevHistory, prevWeekStart, prevWeekEnd).stream()
                    .filter(e -> e.isExpense() && e.isVariable())
                    .mapToLong(Expense::getAmount)
                    .sum();

            FeedbackRenderer.FeedbackContext ctx = new FeedbackRenderer.FeedbackContext(
                    proj.predictedMonthEndWon(), AVAILABLE_BUDGET, savingsImpact,
                    (double) varMtd / AVAILABLE_BUDGET,
                    null,
                    anomalyCategory != null, anomalyCategory, anomalyMag, anomalyWeeklyAmount, anomalyConf,
                    anomalyEmotion,
                    secondaryAnomalyCategory != null, secondaryAnomalyCategory,
                    secondaryAnomalyMag, secondaryAnomalyWeeklyAmount, secondaryConf, secondaryAnomalyEmotionVal,
                    weekEmo,
                    weekTotalWon,
                    prevWeekTotalWon,
                    0.0,
                    overspend,
                    actions,
                    topHighlights,
                    type, bw, overall);

            System.out.printf("%n=== [검증 4] 제%d주차 (%s ~ %s) ===%n",
                    week, weekStart, weekEnd);
            System.out.printf("예측 월말 지출  : %,d원 / 가용예산 %,d원%n",
                    ctx.predictedMonthEndWon(), AVAILABLE_BUDGET);
            System.out.printf("savingsImpact   : %,d원%n", ctx.savingsImpactWon());
            System.out.printf("이상 탐지       : %s%n",
                    ctx.hasAnomaly()
                            ? ctx.anomalyCategoryName() + " (" + String.format("%.1f", ctx.anomalyMagnitude())
                              + "배, " + ctx.anomalyConfidence() + ")"
                            : "없음");
            if (ctx.hasSecondaryAnomaly() && ctx.secondaryAnomalyConfidence() != Confidence.LOW) {
                System.out.printf("2차 이상 탐지   : %s (%.1f배, %s)%n",
                        ctx.secondaryAnomalyCategoryName(),
                        ctx.secondaryAnomalyMagnitude(),
                        ctx.secondaryAnomalyConfidence());
            }
            if (!overspend.isEmpty()) {
                System.out.print("예산 초과 카테고리: ");
                overspend.forEach(o -> System.out.printf("%s +%,d원  ", o.categoryName(), o.overAmountWon()));
                System.out.println();
            }
            System.out.println("-".repeat(60));

            FeedbackRenderer.Rendered result = renderer.render(ctx);
            System.out.println("신뢰도 : " + result.confidence());
            System.out.println();
            System.out.println(result.text());
            renderedCount++;
        }

        System.out.printf("%n총 %d개 ALERT 주차 피드백 생성 완료%n", renderedCount);
        assertThat(renderedCount).isGreaterThanOrEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────
    // [검증 5] 월말 결산 LLM 피드백
    // ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[검증 5] FeedbackRenderer: 월별 월말 결산 피드백 생성")
    void monthEndFeedback_render() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY 미설정 → 스킵");

        AnthropicLlmClient llmClient = new AnthropicLlmClient();
        setField(llmClient, "apiKey", apiKey);
        setField(llmClient, "model", "claude-sonnet-4-5");
        setField(llmClient, "maxTokens", 600);

        ObjectProvider<LlmClient> provider = new ObjectProvider<>() {
            public LlmClient getObject() { return llmClient; }
            public LlmClient getObject(Object... args) { return llmClient; }
            public LlmClient getIfAvailable() { return llmClient; }
            public LlmClient getIfUnique() { return llmClient; }
        };
        FeedbackRenderer renderer = new FeedbackRenderer(provider);

        LocalDate dataEnd = ALL_EXPENSES.stream().map(Expense::getDate).max(Comparator.naturalOrder()).orElseThrow();
        int renderedCount = 0;

        for (Map.Entry<YearMonth, Map<Integer, Long>> entry : BUDGETS_BY_MONTH.entrySet()) {
            YearMonth ym = entry.getKey();
            Map<Integer, Long> catBudget = entry.getValue();
            LocalDate monthStart = ym.atDay(1);
            LocalDate monthEnd   = ym.atEndOfMonth();

            // 해당 월이 데이터에 완전히 포함되어 있어야 결산 가능
            if (monthEnd.isAfter(dataEnd)) continue;

            List<Expense> monthExpenses = ALL_EXPENSES.stream()
                    .filter(e -> !e.getDate().isBefore(monthStart) && !e.getDate().isAfter(monthEnd))
                    .toList();

            long actualTotal = monthExpenses.stream()
                    .filter(Expense::isExpense)
                    .mapToLong(Expense::getAmount).sum();
            if (actualTotal == 0) continue;

            long budgetDiff = AVAILABLE_BUDGET - actualTotal; // 양수=절약, 음수=초과

            // 카테고리별 실적 vs 예산
            Map<Integer, Long> catActual = new HashMap<>();
            for (Expense e : monthExpenses) {
                if (!e.isExpense()) continue;
                catActual.merge(e.getCategoryId(), e.getAmount(), Long::sum);
            }

            List<FeedbackRenderer.CategoryResult> overspend = new ArrayList<>();
            List<FeedbackRenderer.CategoryResult> savings   = new ArrayList<>();
            for (Map.Entry<Integer, Long> catEntry : catBudget.entrySet()) {
                int catId  = catEntry.getKey();
                long budget = catEntry.getValue();
                long actual = catActual.getOrDefault(catId, 0L);
                long diff   = actual - budget;
                if (diff > 0) {
                    overspend.add(new FeedbackRenderer.CategoryResult(Categories.name(catId), diff));
                } else if (diff < 0 && actual > 0) {
                    savings.add(new FeedbackRenderer.CategoryResult(Categories.name(catId), -diff));
                }
            }
            overspend.sort(Comparator.comparingLong(FeedbackRenderer.CategoryResult::amountWon).reversed());
            savings.sort(Comparator.comparingLong(FeedbackRenderer.CategoryResult::amountWon).reversed());

            FeedbackRenderer.MonthEndContext ctx = new FeedbackRenderer.MonthEndContext(
                    ym, actualTotal, AVAILABLE_BUDGET, budgetDiff, overspend, savings);

            System.out.printf("%n=== [검증 5] %s 월말 결산 ===%n", ym);
            System.out.printf("실제 총지출  : %,d원 / 가용예산 %,d원%n", actualTotal, AVAILABLE_BUDGET);
            System.out.printf("예산 대비    : %s %,d원%n",
                    budgetDiff >= 0 ? "절약" : "초과", Math.abs(budgetDiff));
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

            FeedbackRenderer.Rendered result = renderer.renderMonthEnd(ctx);
            System.out.println("신뢰도 : " + result.confidence());
            System.out.println();
            System.out.println(result.text());
            renderedCount++;
        }

        System.out.printf("%n총 %d개월 결산 피드백 생성 완료%n", renderedCount);
        assertThat(renderedCount).isGreaterThanOrEqualTo(0);
    }

    /** 리플렉션으로 private @Value 필드에 값을 주입한다 (테스트 전용). */
    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    /** budgets.csv 로드 → YearMonth → (categoryId → 예산) */
    private static Map<YearMonth, Map<Integer, Long>> loadBudgets() throws Exception {
        Map<YearMonth, Map<Integer, Long>> result = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(
                        WalkForwardValidationTest.class.getResourceAsStream("/testdata/budgets.csv"),
                        "budgets.csv 파일을 찾을 수 없습니다.")))) {
            br.readLine(); // 헤더 스킵 (month,식료품,외식,...)
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length < 12 || cols[0].isBlank()) continue;
                try {
                    YearMonth ym = YearMonth.parse(cols[0].trim(), MONTH_FMT);
                    Map<Integer, Long> budget = new HashMap<>();
                    for (int catId = 1; catId <= 11; catId++) {
                        String val = cols[catId].trim();
                        budget.put(catId, val.isEmpty() ? 0L : Long.parseLong(val));
                    }
                    result.put(ym, budget);
                } catch (Exception ignored) {}
            }
        }
        return result;
    }
}
