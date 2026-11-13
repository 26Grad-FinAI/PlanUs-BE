package com.planus.backend.domain.aifeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.action.SavingsActionCalculator;
import com.planus.backend.domain.aifeedback.ai.ActionEnricher;
import com.planus.backend.domain.aifeedback.cause.CauseSignalAssembler;
import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.ActivityGuard;
import com.planus.backend.domain.aifeedback.core.AnomalyDetector;
import com.planus.backend.domain.aifeedback.core.BudgetProjector;
import com.planus.backend.domain.aifeedback.core.FeedbackTypeResolver;
import com.planus.backend.domain.aifeedback.core.PacingComparator;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.entity.UserProfile;
import com.planus.backend.domain.aifeedback.llm.AnthropicLlmClient;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer;
import com.planus.backend.domain.aifeedback.llm.LlmClient;
import com.planus.backend.domain.aifeedback.llm.LlmRenderMeter;
import com.planus.backend.domain.aifeedback.profile.ProfileUpdater;
import com.planus.backend.domain.aifeedback.repository.AiFeedbackRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.aifeedback.repository.MemoQuestionQueueRepository;
import com.planus.backend.domain.aifeedback.repository.MonthEndVerificationRepository;
import com.planus.backend.domain.aifeedback.repository.UserProfileRepository;
import com.planus.backend.domain.aifeedback.repository.UserReactionRepository;
import com.planus.backend.domain.aifeedback.service.AiFeedbackService;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영 경로 검증 (#111) — {@link AiFeedbackService}를 그대로 호출해 실제 LLM으로 측정한다.
 *
 * <p>{@link WalkForwardValidationTest}는 피드백 컨텍스트를 테스트 안에서 다시 구현해 운영과 정의가 달랐다 (초과액: 실제 vs 월말 예상, 이번 주
 * 금액·건수 집계 기준, 지출 속도 비교 누락, 전월 일평균 계산). 이 테스트는 리포지토리만 CSV 기반 메모리 저장소로 바꾸고 분석·렌더링은 운영 코드를
 * 그대로 실행한다. Spring AI 행동 강화({@link ActionEnricher})만 목 — 결과가 프롬프트에 들어가지 않는다.
 *
 * <p>주차를 순서대로 생성해 프로필·준수율이 운영처럼 누적된다. 반복(LLM_REPEAT)마다 저장소를 새로 만든다.
 *
 * <p>실행: {@code LLM_REPEAT=3 ./gradlew testLlm --tests "*ProductionPathValidationTest"} (LLM_WEEKS로 주차 제한 가능)
 */
@DisplayName("운영 경로 검증 — AiFeedbackService 실제 호출")
class ProductionPathValidationTest {

    private static final long USER_ID = 1L;
    private static final long MONTHLY_INCOME = 1_200_000L;
    private static final long SAVINGS_GOAL = 200_000L;
    private static final int FIRST_WEEK = 17;
    private static final LocalDate DATA_START = LocalDate.of(2026, 2, 1);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final int LLM_REPEAT = Math.max(
            1, Integer.parseInt(Optional.ofNullable(System.getenv("LLM_REPEAT")).orElse("1")));
    private static final Set<Integer> LLM_WEEKS = Optional.ofNullable(System.getenv("LLM_WEEKS"))
            .filter(v -> !v.isBlank())
            .map(v -> Arrays.stream(v.split(","))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .collect(Collectors.toSet()))
            .orElse(Set.of());

    @Test
    @Tag("llm")
    @DisplayName("주간·월말 피드백을 운영 서비스로 생성하고 숫자 후검증 집계")
    void productionPath_render() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY 미설정 → 스킵");

        List<Expense> expenses = loadTransactions();
        Map<YearMonth, Map<Integer, Long>> realBudgets = loadBudgets();
        LocalDate dataEnd = expenses.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Map<YearMonth, Map<Integer, Long>> budgets = withDefaultBudgets(realBudgets, dataEnd);
        LocalDate firstMonday = expenses.stream()
                .map(Expense::getDate)
                .min(Comparator.naturalOrder())
                .orElseThrow()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        AnthropicLlmClient raw = new AnthropicLlmClient();
        setField(raw, "apiKey", apiKey);
        setField(raw, "model", "claude-sonnet-4-5");
        setField(raw, "maxTokens", 600);
        LlmRenderMeter weekly = new LlmRenderMeter();
        LlmRenderMeter monthly = new LlmRenderMeter();
        Map<String, Integer> typeCounts = new TreeMap<>();
        Set<Integer> syntheticWeeks = new java.util.TreeSet<>();

        for (int rep = 1; rep <= LLM_REPEAT; rep++) {
            // 반복마다 새 저장소 — 프로필·피드백 이력이 1주차부터 다시 쌓인다
            Store store = new Store(expenses, budgets);
            LlmRenderMeter[] current = {weekly};
            LlmClient routed = (system, user) -> current[0].wrap(raw).complete(system, user);
            AiFeedbackService service = store.service(routed);

            for (int week = FIRST_WEEK; ; week++) {
                LocalDate weekEnd = firstMonday.plusWeeks(week).minusDays(1);
                if (weekEnd.isAfter(dataEnd)) break;
                if (!LLM_WEEKS.isEmpty() && !LLM_WEEKS.contains(week)) continue;
                current[0] = weekly;
                weekly.begin();
                Optional<AiFeedback> fb = service.generateWeekly(USER_ID, weekEnd);
                fb.ifPresent(f -> typeCounts.merge(f.getFeedbackType(), 1, Integer::sum));
                if (fb.isPresent() && weekly.hasCallsInCurrentRender()) {
                    weekly.end(fb.get().getFeedbackText(), "제" + week + "주차", rep);
                    if (!realBudgets.containsKey(YearMonth.from(weekEnd))) syntheticWeeks.add(week);
                }
            }
            if (!LLM_WEEKS.isEmpty()) continue;
            for (YearMonth ym : realBudgets.keySet()) {
                if (ym.atEndOfMonth().isAfter(dataEnd)) continue;
                current[0] = monthly;
                monthly.begin();
                Optional<AiFeedback> fb = service.generateMonthEnd(USER_ID, ym);
                if (fb.isPresent() && monthly.hasCallsInCurrentRender()) {
                    monthly.end(fb.get().getFeedbackText(), ym + " 월말", rep);
                }
            }
        }

        System.out.printf("%n=== 운영 경로 — 주간 피드백 유형 분포 (반복 %d회 합계) ===%n%s%n", LLM_REPEAT, typeCounts);
        System.out.printf("임의 예산(예산 파일에 없는 달) 주차: %s%n", syntheticWeeks);
        weekly.print("[운영 경로] 주간 피드백 (반복 " + LLM_REPEAT + "회)");
        monthly.print("[운영 경로] 월말 결산 (반복 " + LLM_REPEAT + "회)");
        assertThat(weekly.renders()).isGreaterThan(0);
    }

    @Test
    @Tag("llm")
    @DisplayName("주간 프롬프트 출력 (LLM 호출 없음) — 분석 로직 변경 전후 비교용")
    void productionPath_prompts() throws Exception {
        List<Expense> expenses = loadTransactions();
        LocalDate dataEnd = expenses.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Map<YearMonth, Map<Integer, Long>> budgets = withDefaultBudgets(loadBudgets(), dataEnd);
        LocalDate firstMonday = expenses.stream()
                .map(Expense::getDate)
                .min(Comparator.naturalOrder())
                .orElseThrow()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        String[] last = {null};
        LlmClient fake = (system, user) -> {
            last[0] = user;
            return "이번 주 피드백이에요.";
        };
        AiFeedbackService service = new Store(expenses, budgets).service(fake);

        int printed = 0;
        for (int week = FIRST_WEEK; ; week++) {
            LocalDate weekEnd = firstMonday.plusWeeks(week).minusDays(1);
            if (weekEnd.isAfter(dataEnd)) break;
            last[0] = null;
            service.generateWeekly(USER_ID, weekEnd);
            if (last[0] == null) continue;
            System.out.printf("%n[제%d주차] (%s)%n%s%n", week, weekEnd, last[0]);
            printed++;
        }
        assertThat(printed).isGreaterThan(0);
    }

    @Test
    @DisplayName("[스모크] 가짜 LLM으로 운영 경로 조립 확인 — 35주차 프롬프트에 운영 정의 데이터가 들어감")
    void productionPath_smoke() throws Exception {
        List<Expense> expenses = loadTransactions();
        LocalDate dataEnd = expenses.stream()
                .map(Expense::getDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Map<YearMonth, Map<Integer, Long>> budgets = withDefaultBudgets(loadBudgets(), dataEnd);
        LocalDate firstMonday = expenses.stream()
                .map(Expense::getDate)
                .min(Comparator.naturalOrder())
                .orElseThrow()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        List<String> prompts = new ArrayList<>();
        LlmClient fake = (system, user) -> {
            prompts.add(user);
            return "이번 주 피드백이에요.";
        };
        AiFeedbackService service = new Store(expenses, budgets).service(fake);

        int generated = 0;
        for (int week = FIRST_WEEK; week <= 35; week++) {
            if (service.generateWeekly(USER_ID, firstMonday.plusWeeks(week).minusDays(1))
                    .isPresent()) generated++;
        }
        Optional<AiFeedback> monthEnd = service.generateMonthEnd(USER_ID, YearMonth.of(2026, 7));

        assertThat(generated).isEqualTo(35 - FIRST_WEEK + 1);
        assertThat(monthEnd).isPresent();
        String week35 = prompts.stream()
                .filter(p -> p.contains("롯데월드"))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(week35).contains("29,150원 × 4건").contains("거래처별:");
    }

    /** CSV 기반 메모리 저장소 + 운영 서비스 조립. */
    private static final class Store {
        private final List<Expense> expenses;
        private final Map<YearMonth, Map<Integer, Long>> budgets;
        private final List<AiFeedback> feedbacks = new ArrayList<>();
        private UserProfile profile;

        Store(List<Expense> expenses, Map<YearMonth, Map<Integer, Long>> budgets) {
            this.expenses = expenses;
            this.budgets = budgets;
        }

        AiFeedbackService service(LlmClient llm) {
            UserAccountRepository userRepo = mock(UserAccountRepository.class);
            when(userRepo.findById(USER_ID))
                    .thenReturn(Optional.of(UserAccount.builder()
                            .id(USER_ID)
                            .monthlyIncome(MONTHLY_INCOME)
                            .monthlyFixedExpenses(List.of())
                            .monthlySavingsGoal(SAVINGS_GOAL)
                            .build()));

            ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
            when(expenseRepo.findByUserIdAndExpenseDateBetween(eq(USER_ID), any(), any()))
                    .thenAnswer(inv -> between(inv.getArgument(1), inv.getArgument(2)));
            when(expenseRepo.sumVariableExpensesByPeriod(eq(USER_ID), any(), any()))
                    .thenAnswer(inv -> between(inv.getArgument(1), inv.getArgument(2)).stream()
                            .filter(Expense::isVariable)
                            .mapToLong(Expense::getAmount)
                            .sum());

            BudgetRepository budgetRepo = mock(BudgetRepository.class);
            when(budgetRepo.findByUserIdAndYearMonth(eq(USER_ID), any())).thenAnswer(inv -> {
                YearMonth ym = YearMonth.from((LocalDate) inv.getArgument(1));
                if (!budgets.containsKey(ym)) return Optional.empty();
                return Optional.of(Budget.builder()
                        .id((long) (ym.getYear() * 100 + ym.getMonthValue()))
                        .userId(USER_ID)
                        .yearMonth(ym.atDay(1))
                        .totalBudget(MONTHLY_INCOME - SAVINGS_GOAL)
                        .build());
            });
            when(budgetRepo.existsByUserIdAndYearMonth(eq(USER_ID), any()))
                    .thenAnswer(inv -> budgets.containsKey(YearMonth.from((LocalDate) inv.getArgument(1))));

            BudgetCategoryRepository budgetCatRepo = mock(BudgetCategoryRepository.class);
            when(budgetCatRepo.findByBudgetId(anyLong())).thenAnswer(inv -> {
                long id = inv.getArgument(0);
                YearMonth ym = YearMonth.of((int) (id / 100), (int) (id % 100));
                return budgets.getOrDefault(ym, Map.of()).entrySet().stream()
                        .map(e -> BudgetCategory.builder()
                                .budgetId(id)
                                .categoryId(e.getKey())
                                .amount(e.getValue())
                                .build())
                        .toList();
            });

            AiFeedbackRepository feedbackRepo = mock(AiFeedbackRepository.class);
            when(feedbackRepo.save(any(AiFeedback.class))).thenAnswer(inv -> {
                AiFeedback f = inv.getArgument(0);
                if (!feedbacks.contains(f)) feedbacks.add(f);
                return f;
            });
            when(feedbackRepo.findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(eq(USER_ID), any(), any(), any()))
                    .thenAnswer(inv -> feedbacks.stream()
                            .filter(f -> Objects.equals(f.getPeriodType(), inv.getArgument(1))
                                    && Objects.equals(f.getPeriodStart(), inv.getArgument(2))
                                    && Objects.equals(f.getPeriodEnd(), inv.getArgument(3)))
                            .findFirst());
            when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(eq(USER_ID), any()))
                    .thenAnswer(inv -> feedbacks.stream()
                            .filter(f -> Objects.equals(f.getPeriodType(), inv.getArgument(1)))
                            .sorted(Comparator.comparing(AiFeedback::getPeriodStart)
                                    .reversed())
                            .toList());

            UserProfileRepository profileRepo = mock(UserProfileRepository.class);
            when(profileRepo.findByUserId(USER_ID)).thenAnswer(inv -> Optional.ofNullable(profile));
            when(profileRepo.save(any(UserProfile.class))).thenAnswer(inv -> {
                profile = inv.getArgument(0);
                return profile;
            });

            UserReactionRepository reactionRepo = mock(UserReactionRepository.class);
            when(reactionRepo.findByUserId(USER_ID)).thenReturn(List.of());
            MemoQuestionQueueRepository memoQueueRepo = mock(MemoQuestionQueueRepository.class);
            when(memoQueueRepo.countPendingForWeek(anyLong(), any())).thenReturn(0L);
            when(memoQueueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            MonthEndVerificationRepository monthEndRepo = mock(MonthEndVerificationRepository.class);
            when(monthEndRepo.findByUserIdAndYearMonth(anyLong(), any())).thenReturn(Optional.empty());
            when(monthEndRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TransactionTemplate tx = mock(TransactionTemplate.class);
            when(tx.execute(any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                TransactionCallback<Object> callback = inv.getArgument(0);
                return callback.doInTransaction(null);
            });

            AiFeedbackProperties props = new AiFeedbackProperties(); // 기본값 = application-aifeedback.yml 값
            ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
            ObjectProvider<LlmClient> provider = new ObjectProvider<>() {
                public LlmClient getObject() {
                    return llm;
                }

                public LlmClient getObject(Object... args) {
                    return llm;
                }

                public LlmClient getIfAvailable() {
                    return llm;
                }

                public LlmClient getIfUnique() {
                    return llm;
                }
            };

            return new AiFeedbackService(
                    userRepo,
                    expenseRepo,
                    budgetRepo,
                    budgetCatRepo,
                    feedbackRepo,
                    monthEndRepo,
                    profileRepo,
                    reactionRepo,
                    memoQueueRepo,
                    new AnomalyDetector(props),
                    new BudgetProjector(),
                    new FeedbackRenderer(provider),
                    new ActivityGuard(props),
                    new PacingComparator(),
                    new FeedbackTypeResolver(),
                    new CauseSignalAssembler(objectMapper),
                    new SavingsActionCalculator(props),
                    mock(ActionEnricher.class),
                    new ProfileUpdater(objectMapper),
                    props,
                    objectMapper,
                    Clock.system(ZoneId.of("Asia/Seoul")),
                    tx,
                    mock(ApplicationEventPublisher.class));
        }

        private List<Expense> between(LocalDateTime from, LocalDateTime to) {
            return expenses.stream()
                    .filter(e -> !e.getExpenseDate().isBefore(from)
                            && !e.getExpenseDate().isAfter(to))
                    .toList();
        }
    }

    /**
     * 예산 파일에 없는 달(8~11월)은 10개 카테고리에 가용예산을 균등 배분한 임의 예산으로 채운다 — 운영 서비스는 예산이 없는 달에 피드백을 만들지
     * 않으므로 하네스와 같은 주차를 비교하려면 필요하다. (하네스 WalkForwardValidationTest는 가용예산 ÷ 11을 카테고리 1~11에 넣어, 없는 11번이
     * "기타"로 한 번 더 들어갔다 — 따라 하지 않음.)
     */
    private static Map<YearMonth, Map<Integer, Long>> withDefaultBudgets(
            Map<YearMonth, Map<Integer, Long>> real, LocalDate dataEnd) {
        Map<YearMonth, Map<Integer, Long>> filled = new LinkedHashMap<>(real);
        long each = (MONTHLY_INCOME - SAVINGS_GOAL) / 10;
        for (YearMonth ym = YearMonth.from(DATA_START); !ym.isAfter(YearMonth.from(dataEnd)); ym = ym.plusMonths(1)) {
            if (filled.containsKey(ym)) continue;
            Map<Integer, Long> m = new java.util.HashMap<>();
            for (int cat = 1; cat <= 10; cat++) m.put(cat, each);
            filled.put(ym, m);
        }
        return filled;
    }

    private static List<Expense> loadTransactions() throws Exception {
        List<Expense> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                ProductionPathValidationTest.class.getResourceAsStream("/testdata/transactions.csv"))))) {
            br.readLine();
            String line;
            long id = 1L;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length < 2 || cols[1].isBlank()) continue;
                LocalDate date = LocalDate.parse(cols[0].trim(), DATE_FMT);
                if (date.isBefore(DATA_START)) continue;
                result.add(Expense.builder()
                        .id(id++)
                        .userId(USER_ID)
                        .categoryId(Integer.parseInt(cols[2].trim()))
                        .type("EXPENSE")
                        .amount(Long.parseLong(cols[1].trim()))
                        .expenseDate(date.atStartOfDay())
                        .memo(cols.length > 5 ? cols[5].trim() : "")
                        .emotion(cols[3].trim())
                        .recurring("TRUE".equalsIgnoreCase(cols[4].trim()))
                        .planned(false)
                        .build());
            }
        }
        return result;
    }

    private static Map<YearMonth, Map<Integer, Long>> loadBudgets() throws Exception {
        Map<YearMonth, Map<Integer, Long>> result = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                ProductionPathValidationTest.class.getResourceAsStream("/testdata/budgets.csv"))))) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length < 11 || cols[0].isBlank()) continue;
                Map<Integer, Long> budget = new java.util.HashMap<>();
                for (int catId = 1; catId <= 10; catId++) {
                    String val = cols[catId].trim();
                    budget.put(catId, val.isEmpty() ? 0L : Long.parseLong(val));
                }
                result.put(YearMonth.parse(cols[0].trim(), MONTH_FMT), budget);
            }
        }
        return result;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
