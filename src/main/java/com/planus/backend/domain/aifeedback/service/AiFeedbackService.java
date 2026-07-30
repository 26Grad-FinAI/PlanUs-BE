package com.planus.backend.domain.aifeedback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.action.ActionPlan;
import com.planus.backend.domain.aifeedback.action.SavingsActionCalculator;
import com.planus.backend.domain.aifeedback.cause.CauseSignalAssembler;
import com.planus.backend.domain.aifeedback.cause.CauseSignals;
import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.*;
import com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult;
import com.planus.backend.domain.aifeedback.core.PacingComparator.PastMonth;
import com.planus.backend.domain.aifeedback.entity.*;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.ActionSummary;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.CategoryOverspend;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.CategoryResult;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.FeedbackContext;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.MonthEndContext;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.TransactionHighlight;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.WeekEmotionSummary;
import com.planus.backend.domain.aifeedback.profile.ProfileUpdater;
import com.planus.backend.domain.aifeedback.repository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI-01 주간 파이프라인 오케스트레이터 (v2).
 *
 * <p>파이프라인 단계:
 * <pre>
 * [0]   사용자·예산 로드
 * [1]   지출 데이터 윈도우 조회
 * [1.5] 활동성 가드
 * [2]   페이싱 비교
 * [3]   이상치 탐지
 * [3.5] 메모 질문 큐 적재
 * [4]   예측·저축·burnRate
 * [5]   카테고리별 초과·원인 신호 조립
 * [6]   피드백 유형 결정
 * [7]   절약 액션 산출
 * [8]   FeedbackContext 조립
 * [9]   렌더링
 * [10]  페이로드 JSON 조립 (버전 포함)
 * [11+12] 멱등 upsert + 프로필 갱신 (단일 트랜잭션)
 * </pre>
 *
 * <p>핵심 원칙: "수치·판단은 코드, 문장은 LLM".
 */
@Service
public class AiFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(AiFeedbackService.class);

    private static final String PROMPT_VERSION = "v2.0";
    private static final String LOGIC_VERSION = "v2.0";

    /** 추세 magnitude 계산에 필요한 최소 주 수 (4주 recent + 4주 prior). */
    private static final int TREND_MIN_WEEKS = 8;

    /** PRIMARY를 포함한 피드백에 사용할 HIGH 이상치 최대 개수. */
    private static final int MAX_HIGH_ANOMALIES = 3;

    private final UserAccountRepository userRepo;
    private final ExpenseRepository expenseRepo;
    private final BudgetRepository budgetRepo;
    private final BudgetCategoryRepository budgetCatRepo;
    private final AiFeedbackRepository feedbackRepo;
    private final MonthEndVerificationRepository monthEndVerifRepo;
    private final UserProfileRepository userProfileRepo;
    private final UserReactionRepository reactionRepo;
    private final MemoQuestionQueueRepository memoQueueRepo;
    private final AnomalyDetector detector;
    private final BudgetProjector projector;
    private final FeedbackRenderer renderer;
    private final ActivityGuard activityGuard;
    private final PacingComparator pacingComparator;
    private final FeedbackTypeResolver feedbackTypeResolver;
    private final CauseSignalAssembler causeAssembler;
    private final SavingsActionCalculator savingsCalculator;
    private final ProfileUpdater profileUpdater;
    private final AiFeedbackProperties p;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public AiFeedbackService(
            UserAccountRepository userRepo,
            ExpenseRepository expenseRepo,
            BudgetRepository budgetRepo,
            BudgetCategoryRepository budgetCatRepo,
            AiFeedbackRepository feedbackRepo,
            MonthEndVerificationRepository monthEndVerifRepo,
            UserProfileRepository userProfileRepo,
            UserReactionRepository reactionRepo,
            MemoQuestionQueueRepository memoQueueRepo,
            AnomalyDetector detector,
            BudgetProjector projector,
            FeedbackRenderer renderer,
            ActivityGuard activityGuard,
            PacingComparator pacingComparator,
            FeedbackTypeResolver feedbackTypeResolver,
            CauseSignalAssembler causeAssembler,
            SavingsActionCalculator savingsCalculator,
            ProfileUpdater profileUpdater,
            AiFeedbackProperties p,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionTemplate tx) {
        this.userRepo = userRepo;
        this.expenseRepo = expenseRepo;
        this.budgetRepo = budgetRepo;
        this.budgetCatRepo = budgetCatRepo;
        this.feedbackRepo = feedbackRepo;
        this.monthEndVerifRepo = monthEndVerifRepo;
        this.userProfileRepo = userProfileRepo;
        this.reactionRepo = reactionRepo;
        this.memoQueueRepo = memoQueueRepo;
        this.detector = detector;
        this.projector = projector;
        this.renderer = renderer;
        this.activityGuard = activityGuard;
        this.pacingComparator = pacingComparator;
        this.feedbackTypeResolver = feedbackTypeResolver;
        this.causeAssembler = causeAssembler;
        this.savingsCalculator = savingsCalculator;
        this.profileUpdater = profileUpdater;
        this.p = p;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * 특정 사용자의 주간 AI 소비 피드백을 생성하고 저장한다.
     *
     * @param userId  대상 사용자 ID
     * @param weekEnd 분석 대상 주의 마지막 날(일요일)
     * @return 저장된 피드백, 생성 불가 시 빈 Optional
     */
    public Optional<AiFeedback> generateWeekly(Long userId, LocalDate weekEnd) {
        // ── [0] 사용자·예산 로드 ──
        UserAccount user = userRepo.findById(userId).orElse(null);
        if (user == null) return Optional.empty();

        LocalDate weekStart = weekEnd.minusDays(6);
        LocalDate monthStart = weekEnd.withDayOfMonth(1);
        int dim = weekEnd.lengthOfMonth();
        int day = weekEnd.getDayOfMonth();

        long available = user.availableBudget();
        Map<Integer, Long> catBudget = loadCategoryBudgets(userId, monthStart);
        if (catBudget.isEmpty()) return Optional.empty();

        // ── [1] 지출 데이터 윈도우 조회 ──
        LocalDate queryStart =
                Collections.min(List.of(weekStart.minusWeeks(p.getBaselineWeeks()), monthStart.minusMonths(1)));
        LocalDate queryEnd = weekEnd.withDayOfMonth(dim);
        LocalDateTime from = queryStart.atStartOfDay();
        LocalDateTime to = queryEnd.atTime(LocalTime.MAX);
        List<Expense> window = expenseRepo.findByUserIdAndExpenseDateBetween(userId, from, to);

        // 거부 이력: 한 번만 조회 (프로필 갱신·절약 액션 양쪽에서 사용)
        List<UserReaction> reactions = reactionRepo.findByUserId(userId);

        // 프로필 로드 (원인 신호·절약 액션에서 사용)
        UserProfile profile = userProfileRepo.findByUserId(userId).orElse(null);

        // ── [1.5] 활동성 가드 ──
        List<Long> weeklyCounts = new ArrayList<>();
        for (int w = 1; w <= p.getBaselineWeeks(); w++) {
            LocalDate ws = weekEnd.minusWeeks(w + 1).plusDays(1);
            LocalDate we = weekEnd.minusWeeks(w);
            long count = window.stream()
                    .filter(e -> e.isExpense()
                            && !e.getDate().isBefore(ws)
                            && !e.getDate().isAfter(we))
                    .count();
            weeklyCounts.add(count);
        }
        double normalCount = activityGuard.normalWeeklyCount(weeklyCounts);
        long thisWeekCount = window.stream()
                .filter(e -> e.isExpense()
                        && !e.getDate().isBefore(weekStart)
                        && !e.getDate().isAfter(weekEnd))
                .count();
        boolean isLowData = activityGuard.isLowData(thisWeekCount, normalCount);

        // ── 분기: LOW_DATA면 [2]~[7] 스킵 ──
        FeedbackType feedbackType;
        PacingResult pacing = null;
        Anomaly top = null;
        Confidence anomalyConfidence = Confidence.LOW;
        Map<Integer, Long> overspend = Map.of();
        Map<Integer, CauseSignals> causeSignalsByCategory = Map.of();
        List<ActionPlan> actions = List.of();
        long predictedMonthEnd = 0;
        long savingsImpact = 0;
        double burnRate = 0;
        Confidence overallConfidence;
        List<Anomaly> allAnomalies = List.of();

        if (isLowData) {
            feedbackType = FeedbackType.LOW_DATA;
            overallConfidence = Confidence.LOW;
        } else {
            // ── [2] 페이싱 비교 (변동 지출 / available 예산 — 분자·분모 일관) ──
            long varMtdSpend = sum(window, e -> inMonth(e, monthStart, weekEnd) && e.isVariable());
            pacing = buildPacing(userId, varMtdSpend, available, day, dim, monthStart);

            // ── [3] 이상치 탐지 — 카테고리별 best 수집 후 magnitude 내림차순 정렬 ──
            allAnomalies = detectAllAnomalies(window, weekStart, weekEnd, catBudget);
            top = allAnomalies.isEmpty() ? null : allAnomalies.get(0);

            // ── [3.5] 메모 질문 큐 적재 (mag 기준, memoMinMagnitude 설정) ──
            List<AnomalyCandidate> memoCandidates = collectAnomalyCandidates(window, weekStart, weekEnd, catBudget);
            enqueueMemoQuestions(userId, memoCandidates, weekStart);

            // ── [4] 예측 + 저축 영향 + burnRate ──
            long totalVarMtd = varMtdSpend;
            long totalFixPlanMtd =
                    sum(window, e -> inMonth(e, monthStart, weekEnd) && e.isExpense() && !e.isVariable());
            long remainingFixPlan = estimateRemainingFixedPlanned(window, monthStart, weekEnd);
            double priorRate = priorVariableDailyRate(window, monthStart);
            double[] dowWeights = computeDayOfWeekWeights(window, weekEnd);
            var proj = projector.project(
                    totalVarMtd, totalFixPlanMtd, remainingFixPlan,
                    day, dim, priorRate, dowWeights, monthStart);
            predictedMonthEnd = proj.predictedMonthEndWon();
            savingsImpact = projector.savingsImpact(predictedMonthEnd, available);
            burnRate = available > 0 ? (double) predictedMonthEnd / available : 0;

            // ── [5] 카테고리별 초과 + 원인 신호 조립 ──
            overspend = computeOverspend(window, catBudget, monthStart, weekEnd, day, dim);
            causeSignalsByCategory = new HashMap<>();
            for (int cat : overspend.keySet()) {
                CauseSignals signals = causeAssembler.assemble(cat, window, weekStart, weekEnd, profile);
                causeSignalsByCategory.put(cat, signals);
            }

            // ── [6] 피드백 유형 결정 ──
            anomalyConfidence = top != null
                    ? Confidence.of(
                            top.magnitude(), top.histLength(), p.getConfHighMinSamples(), p.getConfMediumMinSamples())
                    : Confidence.LOW;
            boolean hasHighAnomaly = top != null && anomalyConfidence == Confidence.HIGH;
            feedbackType = feedbackTypeResolver.resolve(false, savingsImpact, hasHighAnomaly, !overspend.isEmpty());

            // ── [7] 절약 액션 ──
            long savingsGap = -savingsImpact;
            if (feedbackType == FeedbackType.ALERT && savingsGap > 0) {
                Map<Integer, Long> categoryMtdSpend = buildCategoryMtdSpend(window, monthStart, weekEnd);
                List<Long> sensitiveAreas = parseSensitiveAreas(profile);
                actions = savingsCalculator.calculate(
                        overspend,
                        savingsGap,
                        day,
                        dim - day,
                        categoryMtdSpend,
                        causeSignalsByCategory,
                        window.stream()
                                .filter(e -> inMonth(e, monthStart, weekEnd) && e.isExpense())
                                .toList(),
                        reactions,
                        sensitiveAreas);
            }

            // overallConfidence: bandWidth + 이상치 신뢰도 기반
            overallConfidence = deriveOverallConfidence(pacing, anomalyConfidence, top != null);
        }

        // ── [5.5] 이번 주 주목 거래 수집 ──
        // 이상치 감지 OR REGRET/IMPULSE 감정 OR 메모 있는 거래, 금액 상위 5건
        List<TransactionHighlight> weekHighlights = isLowData
                ? List.of()
                : collectWeekHighlights(window, weekStart, weekEnd, catBudget);

        // ── [8] FeedbackContext 조립 ──
        final Map<Integer, CauseSignals> finalCauseSignals = causeSignalsByCategory;

        Integer topOverCat = overspend.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        final LocalDate finalWeekStart = weekStart;
        List<CategoryOverspend> overspendList = overspend.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .map(e -> {
                    int cat = e.getKey();
                    long thisWeekAmt = sum(window, ex -> ex.getCategoryId() == cat
                            && !ex.getDate().isBefore(finalWeekStart)
                            && !ex.getDate().isAfter(weekEnd)
                            && ex.isVariable());
                    return new CategoryOverspend(
                            Categories.name(cat), e.getValue(), thisWeekAmt, finalCauseSignals.get(cat));
                })
                .toList();

        List<ActionSummary> actionSummaries = actions.stream()
                .map(a -> new ActionSummary(
                        Categories.name(a.categoryId()), a.reductionWon(), a.dominantFactor(), a.feasibilityScore()))
                .toList();

        // 월 경계를 넘는 주(예: 6/29~7/5)는 BandWidth 톤 억제:
        // weekEnd 기준 월초 판정이 부적절하므로 null로 처리한다.
        BandWidth bandWidth = (pacing != null && weekStart.getMonth() == weekEnd.getMonth())
                ? pacing.bandWidth()
                : null;

        // 이상치 카테고리의 이번 주 실지출액 (LLM에 구체적 금액 제공용)
        final int anomalyCatId = top != null ? top.categoryId() : -1;
        long anomalyWeeklyAmount = anomalyCatId >= 0
                ? sum(window, e -> e.getCategoryId() == anomalyCatId
                        && !e.getDate().isBefore(weekStart)
                        && !e.getDate().isAfter(weekEnd)
                        && e.isVariable())
                : 0L;
        // 이상치 대표 거래의 감정 태그 (LLM 톤 결정용)
        String anomalyEmotion = top != null ? top.rep().getEmotion() : null;

        // 추가 HIGH 이상치: primary 제외 나머지 중 HIGH confidence인 것 (최대 MAX_HIGH_ANOMALIES-1개)
        final LocalDate finalWeekStart2 = weekStart;
        final LocalDate finalWeekEnd2 = weekEnd;
        List<FeedbackRenderer.AnomalyInfo> additionalHighAnomalies = allAnomalies.stream()
                .skip(1)
                .filter(a -> Confidence.of(a.magnitude(), a.histLength(),
                        p.getConfHighMinSamples(), p.getConfMediumMinSamples()) == Confidence.HIGH)
                .limit(MAX_HIGH_ANOMALIES - 1)
                .map(a -> {
                    long weeklyAmt = sum(window, e -> e.getCategoryId() == a.categoryId()
                            && !e.getDate().isBefore(finalWeekStart2)
                            && !e.getDate().isAfter(finalWeekEnd2)
                            && e.isVariable());
                    return new FeedbackRenderer.AnomalyInfo(
                            Categories.name(a.categoryId()),
                            a.magnitude(),
                            weeklyAmt,
                            a.rep().getEmotion());
                })
                .toList();

        // 이번 주 전체 변동지출 감정 분포 (POSITIVE 주차 포함 항상 계산)
        Map<String, Long> weekEmoCounts = new java.util.LinkedHashMap<>();
        Map<String, Long> weekEmoAmounts = new java.util.LinkedHashMap<>();
        long weekUntaggedWon = 0L;
        for (Expense e : window) {
            if (!e.isVariable()) continue;
            if (e.getDate().isBefore(weekStart) || e.getDate().isAfter(weekEnd)) continue;
            String emo = e.getEmotion();
            if (emo != null && !emo.isBlank()) {
                weekEmoCounts.merge(emo, 1L, Long::sum);
                weekEmoAmounts.merge(emo, e.getAmount(), Long::sum);
            } else {
                weekUntaggedWon += e.getAmount();
            }
        }
        WeekEmotionSummary weekEmotion = weekEmoCounts.isEmpty()
                ? null
                : new WeekEmotionSummary(weekEmoCounts, weekEmoAmounts, weekUntaggedWon);

        // 이번 주 총 변동지출 (LLM에 구체적 금액 제공)
        long weekTotalWon = sum(window, e -> e.isVariable()
                && !e.getDate().isBefore(weekStart)
                && !e.getDate().isAfter(weekEnd));

        // 전주 총 변동지출 (전주 대비 증감 계산용)
        LocalDate prevWeekStart = weekStart.minusWeeks(1);
        LocalDate prevWeekEnd = weekEnd.minusWeeks(1);
        long prevWeekTotalWon = sum(window, e -> e.isVariable()
                && !e.getDate().isBefore(prevWeekStart)
                && !e.getDate().isAfter(prevWeekEnd));

        // 예산 준수율 (UserProfile에 저장된 최근 12주 비율)
        double complianceRate = profile != null ? profile.getComplianceRate() : 0.0;

        FeedbackContext ctx = new FeedbackContext(
                predictedMonthEnd,
                available,
                savingsImpact,
                burnRate,
                pacing,
                top != null,
                top != null ? Categories.name(top.categoryId()) : null,
                top != null ? top.magnitude() : 0,
                anomalyWeeklyAmount,
                anomalyConfidence,
                anomalyEmotion,
                additionalHighAnomalies,
                weekEmotion,
                weekTotalWon,
                prevWeekTotalWon,
                complianceRate,
                overspendList,
                actionSummaries,
                weekHighlights,
                feedbackType,
                bandWidth,
                overallConfidence);

        // ── [9] 렌더링 ──
        FeedbackRenderer.Rendered out = renderer.render(ctx);
        LocalDateTime now = LocalDateTime.now(clock);

        // ── [10] 페이로드 JSON (버전 포함) ──
        String payload = buildPayload(feedbackType, pacing, overspend, actions, predictedMonthEnd);

        // ── [11+12] 멱등 upsert + 프로필 갱신 (단일 트랜잭션) ──
        // 동시 실행 시 INSERT 유니크 위반이 발생할 수 있으므로 재시도로 흡수한다.
        final Map<Integer, Long> finalOverspend = overspend;
        final boolean finalIsLowData = isLowData;

        try {
            return Optional.ofNullable(tx.execute(
                    status -> upsertFeedbackAndProfile(
                            userId, weekStart, weekEnd, monthStart, out, topOverCat,
                            feedbackType, finalOverspend, payload, now, reactions, finalIsLowData)));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.info("피드백 중복 삽입 감지 — 재조회 후 갱신: userId={}, weekEnd={}", userId, weekEnd);
            return Optional.ofNullable(tx.execute(
                    status -> upsertFeedbackAndProfile(
                            userId, weekStart, weekEnd, monthStart, out, topOverCat,
                            feedbackType, finalOverspend, payload, now, reactions, finalIsLowData)));
        }
    }

    /**
     * 특정 사용자의 월말 결산 AI 피드백을 생성하고 저장한다.
     *
     * <p>월 확정 지출을 합산하고, 직전 주간 피드백의 예측 총지출과 대조하여
     * {@link MonthEndVerification}을 기록한다. 결산 피드백은 {@code AiFeedback(MONTHLY)}로 저장.
     *
     * @param userId    대상 사용자 ID
     * @param yearMonth 결산 대상 월
     * @return 저장된 피드백, 생성 불가 시 빈 Optional
     */
    public Optional<AiFeedback> generateMonthEnd(Long userId, YearMonth yearMonth) {
        // ── [0] 사용자·예산 로드 ──
        UserAccount user = userRepo.findById(userId).orElse(null);
        if (user == null) return Optional.empty();

        LocalDate monthStart = yearMonth.atDay(1);
        LocalDate monthEnd = yearMonth.atEndOfMonth();
        long available = user.availableBudget();

        Map<Integer, Long> catBudget = loadCategoryBudgets(userId, monthStart);
        if (catBudget.isEmpty()) return Optional.empty();

        // ── [1] 월 전체 지출 조회 ──
        LocalDateTime from = monthStart.atStartOfDay();
        LocalDateTime to = monthEnd.atTime(LocalTime.MAX);
        List<Expense> expenses = expenseRepo.findByUserIdAndExpenseDateBetween(userId, from, to);

        // 실제 총지출 (변동+고정 포함)
        long actualTotal = expenses.stream()
                .filter(Expense::isExpense)
                .mapToLong(Expense::getAmount)
                .sum();
        if (actualTotal == 0) return Optional.empty(); // 지출 없는 달 스킵

        long budgetDiff = available - actualTotal; // 양수=절약, 음수=초과

        // ── [2] 카테고리별 실적 대비 예산 ──
        Map<Integer, Long> catActual = new HashMap<>();
        for (Expense e : expenses) {
            if (!e.isExpense()) continue;
            catActual.merge(e.getCategoryId(), e.getAmount(), Long::sum);
        }

        List<CategoryResult> overspend = new ArrayList<>();
        List<CategoryResult> savings = new ArrayList<>();
        for (var entry : catBudget.entrySet()) {
            int cat = entry.getKey();
            long budget = entry.getValue();
            long actual = catActual.getOrDefault(cat, 0L);
            long diff = actual - budget;
            if (diff > 0) {
                overspend.add(new CategoryResult(Categories.name(cat), diff));
            } else if (diff < 0 && actual > 0) {
                savings.add(new CategoryResult(Categories.name(cat), -diff));
            }
        }
        overspend.sort(Comparator.comparingLong(CategoryResult::amountWon).reversed());
        savings.sort(Comparator.comparingLong(CategoryResult::amountWon).reversed());

        // ── [3] 렌더링 ──
        MonthEndContext ctx = new MonthEndContext(yearMonth, actualTotal, available, budgetDiff, overspend, savings);
        FeedbackRenderer.Rendered out = renderer.renderMonthEnd(ctx);
        LocalDateTime now = LocalDateTime.now(clock);

        // ── [4] MonthEndVerification 저장 ──
        long predictedTotal = loadPredictedTotal(userId, monthStart);
        // 예측값이 없으면 null — 0.0은 "완벽 예측"으로 오독되어 집계 오염
        // 분모는 actualTotal (표준 MAPE 정의)
        Double mape = (predictedTotal > 0 && actualTotal > 0)
                ? Math.abs((double) (actualTotal - predictedTotal) / actualTotal)
                : null;
        saveMonthEndVerification(userId, monthStart, predictedTotal, actualTotal, mape, catActual, catBudget, now);

        // ── [5] AiFeedback(MONTHLY) 멱등 upsert ──
        return Optional.ofNullable(tx.execute(status -> {
            AiFeedback existing = feedbackRepo
                    .findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(userId, "MONTHLY", monthStart, monthEnd)
                    .orElse(null);
            if (existing != null) {
                existing.update(
                        out.text(),
                        out.confidence().name(),
                        null,
                        null,
                        "MONTHLY_SUMMARY",
                        !overspend.isEmpty(),
                        "{}",
                        PROMPT_VERSION,
                        LOGIC_VERSION,
                        now);
                return feedbackRepo.save(existing);
            }
            return feedbackRepo.save(AiFeedback.builder()
                    .userId(userId)
                    .yearMonth(monthStart)
                    .periodType("MONTHLY")
                    .periodStart(monthStart)
                    .periodEnd(monthEnd)
                    .feedbackText(out.text())
                    .confidence(out.confidence().name())
                    .feedbackType("MONTHLY_SUMMARY")
                    .hadOverspend(!overspend.isEmpty())
                    .payload("{}")
                    .promptVersion(PROMPT_VERSION)
                    .logicVersion(LOGIC_VERSION)
                    .createdAt(now)
                    .build());
        }));
    }

    /**
     * 해당 월의 마지막 주간 피드백 payload에서 {@code predictedMonthEnd} 값을 읽는다.
     * 없으면 0 반환.
     */
    private long loadPredictedTotal(Long userId, LocalDate monthStart) {
        return feedbackRepo
                .findByUserIdAndPeriodTypeOrderByPeriodStartDesc(userId, "WEEKLY")
                .stream()
                .filter(f -> f.getYearMonth() != null && f.getYearMonth().equals(monthStart))
                .filter(f -> f.getPayload() != null && !f.getPayload().isBlank())
                .findFirst()
                .map(f -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = objectMapper.readValue(f.getPayload(), Map.class);
                        Object v = map.get("predictedMonthEnd");
                        return v instanceof Number num ? num.longValue() : 0L;
                    } catch (Exception ex) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    /** MonthEndVerification 멱등 upsert. 동일 (userId, yearMonth) 레코드가 있으면 갱신. */
    private void saveMonthEndVerification(
            Long userId,
            LocalDate monthStart,
            long predictedTotal,
            long actualTotal,
            Double mape,
            Map<Integer, Long> catActual,
            Map<Integer, Long> catBudget,
            LocalDateTime now) {
        try {
            String postChange = buildPostSuggestionChange(catActual, catBudget);
            MonthEndVerification existing =
                    monthEndVerifRepo.findByUserIdAndYearMonth(userId, monthStart).orElse(null);
            if (existing == null) {
                monthEndVerifRepo.save(MonthEndVerification.builder()
                        .userId(userId)
                        .yearMonth(monthStart)
                        .predictedTotal(predictedTotal)
                        .actualTotal(actualTotal)
                        .mape(mape)
                        .postSuggestionChange(postChange)
                        .createdAt(now)
                        .build());
            }
            // 이미 존재하면 덮어쓰지 않는다 — 재실행 시 최초 결과를 보존
        } catch (Exception e) {
            log.warn("MonthEndVerification 저장 실패 userId={} month={}: {}", userId, monthStart, e.getMessage());
        }
    }

    /** 카테고리별 실적-예산 차이를 JSON 문자열로 직렬화한다. */
    private String buildPostSuggestionChange(Map<Integer, Long> catActual, Map<Integer, Long> catBudget) {
        try {
            Map<String, Long> diff = new LinkedHashMap<>();
            for (var entry : catBudget.entrySet()) {
                int cat = entry.getKey();
                long actual = catActual.getOrDefault(cat, 0L);
                diff.merge(Categories.name(cat), actual - entry.getValue(), Long::sum);
            }
            return objectMapper.writeValueAsString(diff);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** [11+12] 피드백 멱등 upsert + 프로필 갱신. 트랜잭션 콜백 내부에서 실행. */
    private AiFeedback upsertFeedbackAndProfile(
            Long userId, LocalDate weekStart, LocalDate weekEnd, LocalDate monthStart,
            FeedbackRenderer.Rendered out, Integer topOverCat,
            FeedbackType feedbackType, Map<Integer, Long> overspend,
            String payload, LocalDateTime now,
            List<UserReaction> reactions, boolean isLowData) {

        AiFeedback existing = feedbackRepo
                .findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(userId, "WEEKLY", weekStart, weekEnd)
                .orElse(null);

        AiFeedback fb;
        if (existing != null) {
            existing.update(
                    out.text(),
                    out.confidence().name(),
                    topOverCat != null ? topOverCat.longValue() : null,
                    null,
                    feedbackType.name(),
                    !overspend.isEmpty(),
                    payload,
                    PROMPT_VERSION,
                    LOGIC_VERSION,
                    now);
            fb = feedbackRepo.save(existing);
        } else {
            fb = feedbackRepo.save(AiFeedback.builder()
                    .userId(userId)
                    .yearMonth(monthStart)
                    .periodType("WEEKLY")
                    .periodStart(weekStart)
                    .periodEnd(weekEnd)
                    .feedbackText(out.text())
                    .confidence(out.confidence().name())
                    .feedbackType(feedbackType.name())
                    .hadOverspend(!overspend.isEmpty())
                    .payload(payload)
                    .promptVersion(PROMPT_VERSION)
                    .logicVersion(LOGIC_VERSION)
                    .createdAt(now)
                    .build());
        }

        UserProfile profileEntity = userProfileRepo
                .findByUserId(userId)
                .orElseGet(() -> UserProfile.builder()
                        .userId(userId)
                        .repeatPatterns("{}")
                        .sensitiveAreas("[]")
                        .build());
        List<AiFeedback> recentFeedbacks =
                feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(userId, "WEEKLY");
        profileUpdater.update(profileEntity, overspend.keySet(), reactions, recentFeedbacks, isLowData);
        userProfileRepo.save(profileEntity);

        return fb;
    }

    // ── 헬퍼 ──

    /**
     * 해당 월의 카테고리별 예산을 조회한다.
     *
     * @param userId     사용자 ID
     * @param monthStart 해당 월 1일
     * @return 카테고리 ID → 예산(원) 맵, 예산 미등록 시 빈 맵
     */
    private Map<Integer, Long> loadCategoryBudgets(Long userId, LocalDate monthStart) {
        return budgetRepo
                .findByUserIdAndYearMonth(userId, monthStart)
                .map(b -> {
                    Map<Integer, Long> m = new HashMap<>();
                    for (BudgetCategory bc : budgetCatRepo.findByBudgetId(b.getId()))
                        m.put(bc.getCategoryId(), bc.getAmount());
                    return m;
                })
                .orElse(Map.of());
    }

    /**
     * [2] 페이싱 비교.
     * 분자: 변동 지출, 분모: available(소득−고정−저축) — 동일 기준.
     * 과거 월은 예산 존재 여부({@code existsBy})로 "사용자가 그 달에 활동했는지"를
     * 판별하여, 데이터가 없는 달을 제외한다.
     *
     * <p>과거 월의 available은 현재 available과 같다고 가정한다.
     * (소득·고정·저축목표가 월마다 바뀌면 별도 히스토리 테이블 필요 — v1.1 과제)
     */
    private PacingResult buildPacing(
            Long userId, long varMtdSpend, long available, int day, int dim, LocalDate monthStart) {
        if (available <= 0) return null;

        List<PastMonth> pastMonths = new ArrayList<>();
        for (int m = 1; m <= 3; m++) {
            LocalDate pm = monthStart.minusMonths(m);

            // 해당 월에 예산이 있었는지 = 사용자가 그 달에 활동했는지의 프록시
            if (!budgetRepo.existsByUserIdAndYearMonth(userId, pm)) continue;

            int pmDay = Math.min(day, pm.lengthOfMonth());
            LocalDate pmEnd = pm.plusDays(pmDay - 1);
            long pmMtdSpend =
                    expenseRepo.sumVariableExpensesByPeriod(userId, pm.atStartOfDay(), pmEnd.atTime(LocalTime.MAX));
            pastMonths.add(new PastMonth(pmMtdSpend, pmDay, available));
        }
        return pacingComparator.compare(varMtdSpend, available, day, dim, pastMonths);
    }

    /**
     * [3] 이상치 탐지.
     * spikeProne 필터, histLength(8주 기준선 내 거래 건수) 추적,
     * 추세 magnitude는 4주 vs 4주 대칭 평균 비율로 실계산.
     */
    /**
     * [3] 이상치 탐지 — 카테고리별 best 이상치를 수집해 magnitude 내림차순으로 반환한다.
     * 단일 스캔으로 모든 카테고리를 처리하므로 중복 순회 비용이 없다.
     */
    private List<Anomaly> detectAllAnomalies(
            List<Expense> window, LocalDate weekStart, LocalDate weekEnd, Map<Integer, Long> catBudget) {
        long windowDays = ChronoUnit.DAYS.between(
                window.stream()
                        .filter(Expense::isVariable)
                        .map(Expense::getDate)
                        .min(Comparator.naturalOrder())
                        .orElse(weekEnd),
                weekEnd.plusDays(1));
        if (windowDays / 7 < p.getMinWeeks()) return List.of();

        // 카테고리별 best 이상치 (카테고리당 1개)
        Map<Integer, Anomaly> bestPerCat = new HashMap<>();

        // 카테고리별 변동 거래를 날짜순으로 사전 그룹핑 (1회 순회)
        Map<Integer, List<Expense>> byCat = new HashMap<>();
        for (Expense e : window) {
            if (!e.isVariable()) continue;
            byCat.computeIfAbsent(e.getCategoryId(), k -> new ArrayList<>()).add(e);
        }
        byCat.values().forEach(list -> list.sort(Comparator.comparing(Expense::getDate)));

        // 단발: 이번 주 변동 거래에 대해 같은 카테고리 리스트에서만 히스토리 추출
        for (var entry : byCat.entrySet()) {
            int cat = entry.getKey();
            if (detector.isSpikeProne(cat)) continue;

            List<Expense> catExpenses = entry.getValue();
            long budget = catBudget.getOrDefault(cat, 0L);

            for (Expense e : catExpenses) {
                LocalDate d = e.getDate();
                if (d.isBefore(weekStart) || d.isAfter(weekEnd)) continue;

                double[] hist = baselineHistory(catExpenses, d, cat);
                var z = detector.checkPoint(e.getAmount(), hist, budget);
                if (z.isPresent()) {
                    double mag =
                            hist.length > 0 ? e.getAmount() / Math.max(RobustStats.median(hist), 1) : z.getAsDouble();
                    Anomaly candidate = new Anomaly(cat, e, mag, hist.length);
                    bestPerCat.merge(cat, candidate,
                            (existing, newOne) -> newOne.magnitude() > existing.magnitude() ? newOne : existing);
                }
            }
        }

        // 추세: 고빈도 카테고리 — 4+4 대칭 윈도우
        int weeks = p.getBaselineWeeks();
        if (weeks >= TREND_MIN_WEEKS) {
            for (int cat : catBudget.keySet().stream().filter(detector::isHighFreq).toList()) {
                if (detector.isSpikeProne(cat)) continue;

                double[] weekly = weeklySeries(window, cat, weekEnd, weeks);
                if (detector.trendConfirmed(weekly, weeks - 1, catBudget.getOrDefault(cat, 0L))) {
                    double[] recent4 = Arrays.copyOfRange(weekly, weeks - 4, weeks);
                    double[] prior4 = Arrays.copyOfRange(weekly, weeks - 8, weeks - 4);
                    double rm = RobustStats.mean(recent4);
                    double pm = RobustStats.mean(prior4);
                    double mag = pm > 0 ? rm / pm : p.getTrendRatio();

                    int histLen = (int) byCat.getOrDefault(cat, List.of()).stream()
                            .filter(x -> x.getDate().isBefore(weekStart))
                            .count();

                    List<Expense> catExpenses = byCat.getOrDefault(cat, List.of());
                    Expense rep = catExpenses.stream()
                            .filter(x -> !x.getDate().isBefore(weekStart) && !x.getDate().isAfter(weekEnd))
                            .max(Comparator.comparing(Expense::getExpenseDate))
                            .orElse(null);
                    if (rep != null) {
                        Anomaly candidate = new Anomaly(cat, rep, mag, histLen);
                        bestPerCat.merge(cat, candidate,
                                (existing, newOne) -> newOne.magnitude() > existing.magnitude() ? newOne : existing);
                    }
                }
            }
        }

        List<Anomaly> result = new ArrayList<>(bestPerCat.values());
        result.sort(Comparator.comparingDouble(Anomaly::magnitude).reversed());
        return result;
    }

    /**
     * 카테고리 기준선 히스토리 추출. 세 탐지 경로(detectAllAnomalies, collectAnomalyCandidates,
     * collectWeekHighlights)가 공유하는 단일 정의.
     *
     * <p>기준일(d) 이전 baselineWeeks 이내의 거래만 포함하며,
     * bimodal 카테고리는 bimodalMin 미만 거래를 제외한다.
     */
    private double[] baselineHistory(List<Expense> catExpenses, LocalDate d, int cat) {
        boolean bimodal = p.isBimodal(cat);
        return catExpenses.stream()
                .filter(x -> x.getDate().isBefore(d)
                        && ChronoUnit.DAYS.between(x.getDate(), d) <= p.getBaselineWeeks() * 7L
                        && (!bimodal || x.getAmount() >= p.getBimodalMin()))
                .mapToDouble(Expense::getAmount)
                .toArray();
    }

    /**
     * [3.5] 메모 질문 큐 적재용: mag(중앙값 대비 배수) ≥ memoMinMagnitude인 거래 수집.
     * 메모·감정태그가 없는 거래만 대상.
     *
     * <p>{@code checkPoint()}가 이미 절대금액 하한(pointMin)을 적용하므로
     * 소액 이상치는 여기까지 도달하지 않는다.
     */
    private List<AnomalyCandidate> collectAnomalyCandidates(
            List<Expense> window, LocalDate weekStart, LocalDate weekEnd, Map<Integer, Long> catBudget) {
        List<AnomalyCandidate> candidates = new ArrayList<>();

        Map<Integer, List<Expense>> byCat = new HashMap<>();
        for (Expense e : window) {
            if (!e.isVariable()) continue;
            byCat.computeIfAbsent(e.getCategoryId(), k -> new ArrayList<>()).add(e);
        }
        byCat.values().forEach(list -> list.sort(Comparator.comparing(Expense::getDate)));

        for (var entry : byCat.entrySet()) {
            int cat = entry.getKey();
            if (detector.isSpikeProne(cat)) continue;

            List<Expense> catExpenses = entry.getValue();
            long budget = catBudget.getOrDefault(cat, 0L);

            for (Expense e : catExpenses) {
                LocalDate d = e.getDate();
                if (d.isBefore(weekStart) || d.isAfter(weekEnd)) continue;

                // 메모·감정태그 없는 것만 질문 대상
                if ((e.getMemo() != null && !e.getMemo().isBlank())
                        || (e.getEmotion() != null && !e.getEmotion().isBlank())) {
                    continue;
                }

                double[] hist = baselineHistory(catExpenses, d, cat);
                // 히스토리 없으면 mag 산출 불가 — z-score와 단위가 다르므로 스킵
                if (hist.length == 0) continue;

                var z = detector.checkPoint(e.getAmount(), hist, budget);
                if (z.isPresent()) {
                    double mag = e.getAmount() / Math.max(RobustStats.median(hist), 1);
                    if (mag >= p.getMemoMinMagnitude()) {
                        candidates.add(new AnomalyCandidate(e, mag));
                    }
                }
            }
        }
        return candidates;
    }

    /** [3.5] 메모 질문 큐 적재. 금액 내림차순으로 주당 상한까지만. */
    private void enqueueMemoQuestions(Long userId, List<AnomalyCandidate> candidates, LocalDate weekStart) {
        if (candidates.isEmpty()) return;

        long pending = memoQueueRepo.countPendingForWeek(userId, weekStart);
        int cap = p.getWeeklyQuestionCap();
        int remaining = (int) Math.max(0, cap - pending);
        if (remaining <= 0) return;

        List<AnomalyCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparingLong((AnomalyCandidate c) -> c.expense().getAmount())
                        .reversed())
                .limit(remaining)
                .toList();

        for (AnomalyCandidate ac : sorted) {
            Expense e = ac.expense();
            memoQueueRepo.save(MemoQuestionQueue.builder()
                    .userId(userId)
                    .expenseId(e.getId())
                    .categoryId((long) e.getCategoryId())
                    .anomalyMagnitude(ac.magnitude())
                    .questionWeek(weekStart)
                    .build());
        }
    }

    /** 카테고리별 초과 예상(선형 근사: 변동 MTD를 월말까지 외삽 + 고정·예정 MTD). */
    private Map<Integer, Long> computeOverspend(
            List<Expense> window,
            Map<Integer, Long> catBudget,
            LocalDate monthStart,
            LocalDate weekEnd,
            int day,
            int dim) {
        Map<Integer, Long> overspend = new HashMap<>();
        for (var entry : catBudget.entrySet()) {
            int cat = entry.getKey();
            long varMtd =
                    sum(window, e -> e.getCategoryId() == cat && inMonth(e, monthStart, weekEnd) && e.isVariable());
            long fixMtd = sum(
                    window,
                    e -> e.getCategoryId() == cat
                            && inMonth(e, monthStart, weekEnd)
                            && e.isExpense()
                            && !e.isVariable());
            long predictedCat = Math.round((double) varMtd / Math.max(day, 1) * dim) + fixMtd;
            long over = predictedCat - entry.getValue();
            if (over > 0) overspend.put(cat, over);
        }
        return overspend;
    }

    /**
     * 카테고리별 이번 달 누적 변동 지출. 고정 지출은 감축 불가이므로 제외.
     * [7] SavingsActionCalculator의 남은 기간 감축가능액 계산에 사용.
     */
    private Map<Integer, Long> buildCategoryMtdSpend(List<Expense> window, LocalDate monthStart, LocalDate weekEnd) {
        Map<Integer, Long> map = new HashMap<>();
        for (Expense e : window) {
            if (!e.isVariable() || !inMonth(e, monthStart, weekEnd)) continue;
            map.merge(e.getCategoryId(), e.getAmount(), Long::sum);
        }
        return map;
    }

    /**
     * overallConfidence 파생. bandWidth + 이상치 신뢰도 기반.
     * WIDE→LOW, MEDIUM→MEDIUM, NARROW→HIGH. 이상치 신뢰도와 lower() 적용.
     */
    private Confidence deriveOverallConfidence(
            PacingResult pacing, Confidence anomalyConfidence, boolean hasAnomaly) {
        Confidence base;
        if (pacing != null) {
            base = switch (pacing.bandWidth()) {
                case WIDE -> Confidence.LOW;
                case MEDIUM -> Confidence.MEDIUM;
                case NARROW -> Confidence.HIGH;
            };
        } else {
            base = Confidence.LOW;
        }
        if (hasAnomaly) {
            return Confidence.lower(base, anomalyConfidence);
        }
        return base;
    }

    /** UserProfile의 sensitiveAreas JSON을 파싱한다. ProfileUpdater의 공용 헬퍼 재사용. */
    private List<Long> parseSensitiveAreas(UserProfile profile) {
        return ProfileUpdater.parseSensitiveAreaIds(objectMapper, profile);
    }

    /** 페이로드 JSON 조립 (버전 포함). */
    private String buildPayload(
            FeedbackType feedbackType,
            PacingResult pacing,
            Map<Integer, Long> overspend,
            List<ActionPlan> actions,
            long predictedMonthEnd) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", Map.of("prompt", PROMPT_VERSION, "logic", LOGIC_VERSION));
            payload.put("feedbackType", feedbackType.name());
            payload.put("predictedMonthEnd", predictedMonthEnd);
            if (pacing != null) {
                payload.put(
                        "pacing",
                        Map.of(
                                "ratio", pacing.pacingRatio(),
                                "band", pacing.bandWidth().name(),
                                "monthsAvailable", pacing.monthsAvailable()));
            }
            payload.put("overspend", overspend);
            payload.put(
                    "actions",
                    actions.stream()
                            .map(a -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("categoryId", a.categoryId());
                                m.put("reductionWon", a.reductionWon());
                                m.put("dominantFactor", a.dominantFactor());
                                m.put("feasibilityScore", a.feasibilityScore());
                                return m;
                            })
                            .toList());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("페이로드 직렬화 실패", e);
            return "{}";
        }
    }

    /** 카테고리 주간 합계(변동만), oldest→current, 길이 weeks. 현재 주 = 인덱스 weeks-1. */
    private double[] weeklySeries(List<Expense> window, int cat, LocalDate weekEnd, int weeks) {
        double[] arr = new double[weeks];
        for (Expense e : window) {
            if (!e.isVariable() || e.getCategoryId() != cat) continue;
            long daysAgo = ChronoUnit.DAYS.between(e.getDate(), weekEnd);
            if (daysAgo < 0) continue;
            int w = (int) (weeks - 1 - daysAgo / 7);
            if (w >= 0 && w < weeks) arr[w] += e.getAmount();
        }
        return arr;
    }

    /**
     * 월말까지 남은 고정·예정 지출 합계를 추정한다.
     *
     * @param window     기준선 윈도우 거래 목록
     * @param monthStart 해당 월 1일
     * @param weekEnd    분석 주 마지막 날
     * @return 추정 남은 고정·예정 지출 합계(원)
     */
    private long estimateRemainingFixedPlanned(List<Expense> window, LocalDate monthStart, LocalDate weekEnd) {
        long plannedFuture = window.stream()
                .filter(e -> e.isPlanned()
                        && !e.getDate().isBefore(monthStart)
                        && e.getDate().isAfter(weekEnd))
                .mapToLong(Expense::getAmount)
                .sum();
        LocalDate prevMonth = monthStart.minusMonths(1);
        long recurringRemaining = window.stream()
                .filter(e -> e.isRecurring()
                        && !e.getDate().isBefore(prevMonth)
                        && e.getDate().isBefore(monthStart))
                .filter(e -> e.getDate().getDayOfMonth() > weekEnd.getDayOfMonth())
                .mapToLong(Expense::getAmount)
                .sum();
        return plannedFuture + recurringRemaining;
    }

    /**
     * 전월 변동 지출 일평균을 계산한다. 예측 블렌딩에 사용.
     *
     * @param window     기준선 윈도우 거래 목록
     * @param monthStart 이번 달 1일
     * @return 전월 변동 지출 일평균(원), 거래 없으면 0.0
     */
    private double priorVariableDailyRate(List<Expense> window, LocalDate monthStart) {
        LocalDate prevMonth = monthStart.minusMonths(1);
        long sum = window.stream()
                .filter(e -> e.isVariable()
                        && !e.getDate().isBefore(prevMonth)
                        && e.getDate().isBefore(monthStart))
                .mapToLong(Expense::getAmount)
                .sum();
        int days = prevMonth.lengthOfMonth();
        return sum > 0 ? (double) sum / days : 0.0;
    }

    private static final int MIN_DOW_SAMPLE = 20;
    private static final double MIN_DOW_WEIGHT = 0.1;

    /**
     * 윈도우 내 변동 지출에서 요일별 가중치를 산출한다.
     * 가중치 = 해당 요일 일평균(=합계/날짜수) / 전체 일평균.
     * 전체 거래 건수가 MIN_DOW_SAMPLE 미만이면 null → BudgetProjector가 균등 처리.
     *
     * @return 길이 7 배열 (인덱스 0=월 ~ 6=일, 평균 1.0으로 정규화), 또는 null
     */
    private double[] computeDayOfWeekWeights(List<Expense> window, LocalDate weekEnd) {
        LocalDate cutoff = weekEnd.minusWeeks(p.getBaselineWeeks());

        // 1단계: 기간 내 각 요일이 몇 번 등장하는지 (날짜 수)
        int[] dayCountByDow = new int[7];
        for (LocalDate d = cutoff; !d.isAfter(weekEnd); d = d.plusDays(1)) {
            dayCountByDow[d.getDayOfWeek().getValue() - 1]++;
        }

        // 2단계: 요일별 변동 지출 합계
        long[] sumByDow = new long[7];
        int totalTxCount = 0;
        for (Expense e : window) {
            if (!e.isVariable()) continue;
            LocalDate d = e.getDate();
            if (d.isAfter(weekEnd) || d.isBefore(cutoff)) continue;
            sumByDow[d.getDayOfWeek().getValue() - 1] += e.getAmount();
            totalTxCount++;
        }

        // 가드: 전체 거래 건수가 너무 적으면 가중치 신뢰 불가
        if (totalTxCount < MIN_DOW_SAMPLE) return null;

        // 3단계: 요일별 일평균 (합계 / 날짜 수)
        double[] avgByDow = new double[7];
        double totalAvg = 0;
        for (int i = 0; i < 7; i++) {
            avgByDow[i] = dayCountByDow[i] > 0
                    ? (double) sumByDow[i] / dayCountByDow[i]
                    : 0.0;
            totalAvg += avgByDow[i];
        }
        totalAvg /= 7;

        if (totalAvg <= 0) return null;

        // 4단계: 가중치 = 일평균 / 전체평균, 하한 적용
        double[] weights = new double[7];
        for (int i = 0; i < 7; i++) {
            weights[i] = Math.max(avgByDow[i] / totalAvg, MIN_DOW_WEIGHT);
        }

        // 5단계: 재정규화 — 가중치 평균이 1.0이 되도록 (합 = 7.0)
        double weightSum = 0;
        for (double w : weights) weightSum += w;
        for (int i = 0; i < 7; i++) {
            weights[i] = weights[i] * 7.0 / weightSum;
        }

        return weights;
    }

    /**
     * [5.5] 이번 주 주목 거래 수집.
     *
     * <p>선택 기준 (OR): 이상치 감지됨 / REGRET·IMPULSE 감정 태그 / 메모 있음.
     * 금액 내림차순 상위 5건으로 제한. spikeProne 카테고리는 이상치 판단에서 제외.
     */
    private List<TransactionHighlight> collectWeekHighlights(
            List<Expense> window, LocalDate weekStart, LocalDate weekEnd, Map<Integer, Long> catBudget) {

        // 카테고리별 히스토리 사전 구성 (변동 지출만)
        Map<Integer, List<Expense>> byCat = new HashMap<>();
        for (Expense e : window) {
            if (!e.isVariable()) continue;
            byCat.computeIfAbsent(e.getCategoryId(), k -> new ArrayList<>()).add(e);
        }
        byCat.values().forEach(list -> list.sort(Comparator.comparing(Expense::getDate)));

        List<TransactionHighlight> result = new ArrayList<>();
        for (Expense e : window) {
            LocalDate d = e.getDate();
            if (d.isBefore(weekStart) || d.isAfter(weekEnd) || !e.isVariable()) continue;

            int cat = e.getCategoryId();
            boolean isAnomaly = false;
            if (!detector.isSpikeProne(cat)) {
                long budget = catBudget.getOrDefault(cat, 0L);
                double[] hist = baselineHistory(byCat.getOrDefault(cat, List.of()), d, cat);
                isAnomaly = detector.checkPoint(e.getAmount(), hist, budget).isPresent();
            }

            if (isAnomaly || isHighlightEmotion(e.getEmotion())
                    || (e.getMemo() != null && !e.getMemo().isBlank())) {
                result.add(new TransactionHighlight(
                        d,
                        Categories.name(cat),
                        e.getAmount(),
                        e.getMemo(),
                        e.getEmotion()));
            }
        }

        return result.stream()
                .sorted(Comparator.comparingLong(TransactionHighlight::amountWon).reversed())
                .limit(5)
                .toList();
    }

    private static boolean isHighlightEmotion(String emotion) {
        return emotion != null
                && (emotion.equalsIgnoreCase("REGRET") || emotion.equalsIgnoreCase("IMPULSE"));
    }

    private interface Pred {
        boolean test(Expense e);
    }

    private long sum(List<Expense> xs, Pred f) {
        long s = 0;
        for (Expense e : xs) if (f.test(e)) s += e.getAmount();
        return s;
    }

    private boolean inMonth(Expense e, LocalDate monthStart, LocalDate weekEnd) {
        LocalDate d = e.getDate();
        return !d.isBefore(monthStart) && !d.isAfter(weekEnd);
    }

    /** 이상치 탐지 결과. histLength = 8주 기준선 내 거래 건수 (Confidence 산출용). */
    private record Anomaly(int categoryId, Expense rep, double magnitude, int histLength) {}

    /** [3.5] 메모 질문 큐 적재 후보. */
    private record AnomalyCandidate(Expense expense, double magnitude) {}
}
