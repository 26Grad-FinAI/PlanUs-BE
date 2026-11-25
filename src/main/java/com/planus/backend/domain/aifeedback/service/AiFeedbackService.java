package com.planus.backend.domain.aifeedback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.action.ActionPlan;
import com.planus.backend.domain.aifeedback.action.SavingsActionCalculator;
import com.planus.backend.domain.aifeedback.ai.ActionEnricher;
import com.planus.backend.domain.aifeedback.ai.EnrichedAction;
import com.planus.backend.domain.aifeedback.cause.CauseSignalAssembler;
import com.planus.backend.domain.aifeedback.cause.CauseSignals;
import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.*;
import com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult;
import com.planus.backend.domain.aifeedback.core.PacingComparator.PastMonth;
import com.planus.backend.domain.aifeedback.entity.*;
import com.planus.backend.domain.aifeedback.event.FeedbackCreatedEvent;
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
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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

    /** v3.0 (#111): 배수 기준 명시, 건수·묶음 표기, 절약 액션 출처 제한, 내부 용어 제거, 거래처별 건수. */
    private static final String PROMPT_VERSION = "v4.6";

    private static final String LOGIC_VERSION = "v2.5";

    /** 피드백의 '평소' = 이번 주를 뺀 직전 {@value}주 주당 평균 (#144). */
    static final int RECENT_WEEKS = 4;

    static final int RECENT_MIN_ACTIVE_WEEKS = 3;

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
    private final ActionEnricher actionEnricher;
    private final ProfileUpdater profileUpdater;
    private final AiFeedbackProperties p;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher eventPublisher;

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
            ActionEnricher actionEnricher,
            ProfileUpdater profileUpdater,
            AiFeedbackProperties p,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionTemplate tx,
            ApplicationEventPublisher eventPublisher) {
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
        this.actionEnricher = actionEnricher;
        this.profileUpdater = profileUpdater;
        this.p = p;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = tx;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 특정 사용자의 주간 AI 소비 피드백을 생성하고 저장한다.
     *
     * @param userId  대상 사용자 ID
     * @param weekEnd 분석 대상 주의 마지막 날(일요일)
     * @return 저장된 피드백, 생성 불가 시 빈 Optional
     */
    public Optional<AiFeedback> generateWeekly(Long userId, LocalDate weekEnd) {
        return generateWeekly(userId, weekEnd, false);
    }

    /**
     * 주간 피드백을 생성한다. 같은 주 피드백이 이미 저장돼 있으면 새로 만들지 않고 그대로 반환한다 —
     * 재실행해도 LLM 호출과 {@link FeedbackCreatedEvent}(절약 시뮬레이션 재실행)가 반복되지 않는다 (#130).
     *
     * @param userId  대상 사용자 ID
     * @param weekEnd 분석 대상 주의 마지막 날(일요일)
     * @param force   true면 기존 피드백이 있어도 다시 생성해 덮어쓴다 (개발·검증용)
     * @return 저장된(또는 기존) 피드백, 생성 불가 시 빈 Optional
     */
    public Optional<AiFeedback> generateWeekly(Long userId, LocalDate weekEnd, boolean force) {
        LocalDate weekStart = weekEnd.minusDays(6);

        if (!force) {
            Optional<AiFeedback> existing = feedbackRepo.findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(
                    userId, "WEEKLY", weekStart, weekEnd);
            if (existing.isPresent()) {
                log.info("주간 피드백 이미 존재 — 생성 생략: userId={}, weekEnd={}", userId, weekEnd);
                return existing;
            }
        }

        // ── [0] 사용자·예산 로드 ──
        UserAccount user = userRepo.findById(userId).orElse(null);
        if (user == null) return Optional.empty();

        LocalDate monthStart = weekEnd.withDayOfMonth(1);
        int dim = weekEnd.lengthOfMonth();
        int day = weekEnd.getDayOfMonth();

        long available = user.availableBudget();
        Map<Integer, Long> catBudget = loadCategoryBudgets(userId, monthStart);
        if (catBudget.isEmpty()) return Optional.empty();

        // ── [1] 지출 데이터 윈도우 조회 ──
        LocalDate queryStart = Collections.min(
                List.of(weekStart.minusWeeks(p.getBaselineWeeks()), monthStart.minusMonths(USUAL_MONTHS)));
        LocalDate queryEnd = weekEnd.withDayOfMonth(dim);
        LocalDateTime from = queryStart.atStartOfDay();
        LocalDateTime to = queryEnd.atTime(LocalTime.MAX);
        List<Expense> window = expenseRepo.findByUserIdAndExpenseDateBetween(userId, from, to);

        // 거부 이력: 한 번만 조회 (프로필 갱신·절약 액션 양쪽에서 사용). 만료된 거절은 뺀다 (#141)
        LocalDateTime reactionNow = LocalDateTime.now(clock);
        List<UserReaction> reactions = reactionRepo.findByUserId(userId).stream()
                .filter(r -> r.isActiveAt(reactionNow, p.getRejectionExpiryMonths()))
                .toList();

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
        // 예산이 배분되지 않은 카테고리(기타)의 평소 월 지출 — 초과를 예산 대신 평소와 비교한다 (#141)
        Map<Integer, Long> usualMonthly = new HashMap<>();
        Map<Integer, CauseSignals> causeSignalsByCategory = Map.of();
        List<ActionPlan> actions = List.of();
        Map<Integer, Long> categoryRemainingExpected = Map.of();
        List<EnrichedAction> enrichedActions = List.of();
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
                    totalVarMtd, totalFixPlanMtd, remainingFixPlan, day, dim, priorRate, dowWeights, monthStart);
            predictedMonthEnd = proj.predictedMonthEndWon();
            savingsImpact = projector.savingsImpact(predictedMonthEnd, available);
            burnRate = available > 0 ? (double) predictedMonthEnd / available : 0;

            // ── [5] 카테고리별 초과 + 원인 신호 조립 ──
            overspend = computeOverspend(window, catBudget, monthStart, weekEnd, day, dim, dowWeights, usualMonthly);
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
                categoryRemainingExpected =
                        buildCategoryRemainingExpected(window, monthStart, weekEnd, day, dim, dowWeights);
                // 프로필 값이 아니라 지금 효력 있는 반응에서 바로 계산한다 — 프로필은 이 실행 뒤에 갱신되므로, 새 거절이 한 주기 늦게 반영되는 걸 막는다
                List<Long> sensitiveAreas = new ArrayList<>(ProfileUpdater.sensitiveCategories(reactions));
                actions = savingsCalculator.calculate(
                        overspend,
                        savingsGap,
                        day,
                        dim - day,
                        categoryRemainingExpected,
                        causeSignalsByCategory,
                        window.stream()
                                .filter(e -> inMonth(e, monthStart, weekEnd) && e.isExpense())
                                .toList(),
                        reactions,
                        sensitiveAreas);
            }

            // ── [7.5] Spring AI 행동 강화 ──
            if (!actions.isEmpty()) {
                try {
                    enrichedActions = actionEnricher.enrich(userId, weekEnd, actions);
                } catch (Exception e) {
                    log.warn("Spring AI 행동 강화 실패, 기본 actions 사용: userId={}", userId, e);
                    enrichedActions = actionEnricher.toBasicEnriched(actions);
                }
            }

            // overallConfidence: bandWidth + 이상치 신뢰도 기반
            overallConfidence = deriveOverallConfidence(pacing, anomalyConfidence, top != null);
        }

        // ── [5.5] 이번 주 주목 거래 수집 ──
        // 이상치 감지 OR REGRET/IMPULSE 감정 OR 메모 있는 거래, 금액 상위 5건
        List<TransactionHighlight> weekHighlights =
                isLowData ? List.of() : collectWeekHighlights(window, weekStart, weekEnd, catBudget);
        // '가장 컸다' 검증용 — 주목 거래 필터(이상치·감정·메모)와 무관하게 이번 주 변동지출 중 가장 큰 한 건
        TransactionHighlight weekLargest = isLowData ? null : findWeekLargest(window, weekStart, weekEnd);

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
                    Pred thisWeekVar = ex -> ex.getCategoryId() == cat
                            && !ex.getDate().isBefore(finalWeekStart)
                            && !ex.getDate().isAfter(weekEnd)
                            && ex.isVariable();
                    List<String> memos = new ArrayList<>();
                    for (Expense ex : window) if (thisWeekVar.test(ex)) memos.add(ex.getMemo());
                    Expense big = largestSingleOfOverspend(window, cat, monthStart, weekEnd, e.getValue());
                    return new CategoryOverspend(
                            Categories.name(cat),
                            e.getValue(),
                            sum(window, thisWeekVar),
                            finalCauseSignals.get(cat),
                            count(window, thisWeekVar),
                            MerchantNames.countByMerchant(memos),
                            usualMonthly.getOrDefault(cat, 0L),
                            big != null ? big.getAmount() : 0L,
                            big != null
                                            && big.getMemo() != null
                                            && !big.getMemo().isBlank()
                                    ? MerchantNames.countByMerchant(List.of(big.getMemo()))
                                            .keySet()
                                            .iterator()
                                            .next()
                                    : null);
                })
                .toList();

        final Map<Integer, Long> finalRemainingExpected = categoryRemainingExpected;
        List<ActionSummary> actionSummaries = actions.stream()
                .map(a -> new ActionSummary(
                        Categories.name(a.categoryId()),
                        a.reductionWon(),
                        a.dominantFactor(),
                        a.feasibilityScore(),
                        finalRemainingExpected.getOrDefault(a.categoryId(), 0L)))
                .toList();

        // 월 경계를 넘는 주(예: 6/29~7/5)는 BandWidth 톤 억제:
        // weekEnd 기준 월초 판정이 부적절하므로 null로 처리한다.
        BandWidth bandWidth =
                (pacing != null && weekStart.getMonth() == weekEnd.getMonth()) ? pacing.bandWidth() : null;

        // 이상치 카테고리의 이번 주 실지출액 (LLM에 구체적 금액 제공용)
        final int anomalyCatId = top != null ? top.categoryId() : -1;
        final LocalDate anomalyWeekStart = weekStart;
        Pred anomalyThisWeek = e -> e.getCategoryId() == anomalyCatId
                && !e.getDate().isBefore(anomalyWeekStart)
                && !e.getDate().isAfter(weekEnd)
                && e.isVariable();
        long anomalyWeeklyAmount = anomalyCatId >= 0 ? sum(window, anomalyThisWeek) : 0L;
        int anomalyWeeklyCount = anomalyCatId >= 0 ? count(window, anomalyThisWeek) : 0;
        // 이상치 대표 거래의 감정 태그 (LLM 톤 결정용)
        String anomalyEmotion = top != null ? top.rep().getEmotion() : null;

        // 추가 HIGH 이상치: primary 제외 나머지 중 HIGH confidence인 것 (최대 MAX_HIGH_ANOMALIES-1개)
        final LocalDate finalWeekStart2 = weekStart;
        final LocalDate finalWeekEnd2 = weekEnd;
        List<FeedbackRenderer.AnomalyInfo> additionalHighAnomalies = allAnomalies.stream()
                .skip(1)
                .filter(a -> Confidence.of(
                                a.magnitude(), a.histLength(), p.getConfHighMinSamples(), p.getConfMediumMinSamples())
                        == Confidence.HIGH)
                .limit(MAX_HIGH_ANOMALIES - 1)
                .map(a -> {
                    Pred catThisWeek = e -> e.getCategoryId() == a.categoryId()
                            && !e.getDate().isBefore(finalWeekStart2)
                            && !e.getDate().isAfter(finalWeekEnd2)
                            && e.isVariable();
                    long weeklyAmt = sum(window, catThisWeek);
                    return new FeedbackRenderer.AnomalyInfo(
                            Categories.name(a.categoryId()),
                            a.magnitude(),
                            weeklyAmt,
                            a.rep().getEmotion(),
                            withBaselineWeeks(a.basis()),
                            count(window, catThisWeek));
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
        WeekEmotionSummary weekEmotion =
                weekEmoCounts.isEmpty() ? null : new WeekEmotionSummary(weekEmoCounts, weekEmoAmounts, weekUntaggedWon);

        // 이번 주 총 변동지출 (LLM에 구체적 금액 제공)
        long weekTotalWon = sum(
                window,
                e -> e.isVariable()
                        && !e.getDate().isBefore(weekStart)
                        && !e.getDate().isAfter(weekEnd));

        // 전주 총 변동지출 (전주 대비 증감 계산용)
        LocalDate prevWeekStart = weekStart.minusWeeks(1);
        LocalDate prevWeekEnd = weekEnd.minusWeeks(1);
        long prevWeekTotalWon = sum(
                window,
                e -> e.isVariable()
                        && !e.getDate().isBefore(prevWeekStart)
                        && !e.getDate().isAfter(prevWeekEnd));

        // 직전 4주 주당 평균 변동지출 (#144) — 피드백의 '평소'. 이번 주는 빼고, 지출이 있는 주가 3주 미만이면 기준으로 쓰지 않는다
        long recent4WeekAvgWon = recentWeeklyAverage(window, weekStart, RECENT_WEEKS, RECENT_MIN_ACTIVE_WEEKS);

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
                overallConfidence,
                top != null ? withBaselineWeeks(top.basis()) : null,
                anomalyWeeklyCount,
                dim - day,
                recent4WeekAvgWon,
                weekLargest);

        // ── [9] 렌더링 ──
        FeedbackRenderer.Rendered out = renderer.render(ctx);
        LocalDateTime now = LocalDateTime.now(clock);

        // ── [10] 페이로드 JSON (버전 포함) ──
        String payload = buildPayload(feedbackType, pacing, overspend, actions, enrichedActions, predictedMonthEnd);

        // ── [11+12] 멱등 upsert + 프로필 갱신 (단일 트랜잭션) ──
        // 동시 실행 시 INSERT 유니크 위반이 발생할 수 있으므로 재시도로 흡수한다.
        final Map<Integer, Long> finalOverspend = overspend;
        final boolean finalIsLowData = isLowData;

        try {
            return Optional.ofNullable(tx.execute(status -> upsertFeedbackAndProfile(
                    userId,
                    weekStart,
                    weekEnd,
                    monthStart,
                    out,
                    topOverCat,
                    feedbackType,
                    finalOverspend,
                    payload,
                    now,
                    reactions,
                    finalIsLowData)));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.info("피드백 중복 삽입 감지 — 재조회 후 갱신: userId={}, weekEnd={}", userId, weekEnd);
            return Optional.ofNullable(tx.execute(status -> upsertFeedbackAndProfile(
                    userId,
                    weekStart,
                    weekEnd,
                    monthStart,
                    out,
                    topOverCat,
                    feedbackType,
                    finalOverspend,
                    payload,
                    now,
                    reactions,
                    finalIsLowData)));
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
        // 예산이 배분되지 않은 카테고리(기타)는 직전 3개월 월평균과 비교한다 (#141)
        List<Expense> history = catBudget.containsValue(0L)
                ? expenseRepo.findByUserIdAndExpenseDateBetween(
                        userId,
                        monthStart.minusMonths(USUAL_MONTHS).atStartOfDay(),
                        monthStart.minusDays(1).atTime(LocalTime.MAX))
                : List.of();

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
            boolean vsUsual = budget <= 0;
            if (vsUsual) {
                budget = usualMonthlyTotal(history, monthStart, cat);
                if (budget <= 0) continue; // 평소를 알 수 없으면 판정하지 않는다
            }
            long actual = catActual.getOrDefault(cat, 0L);
            long diff = actual - budget;
            if (diff > 0) {
                overspend.add(new CategoryResult(Categories.name(cat), diff, vsUsual));
            } else if (diff < 0 && actual > 0) {
                savings.add(new CategoryResult(Categories.name(cat), -diff, vsUsual));
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
        Optional<AiFeedback> result = Optional.ofNullable(tx.execute(status -> {
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

        // 월간 피드백은 enrichedActions가 없으므로(payload="{}") 시뮬레이션 이벤트를 발행하지 않는다.
        return result;
    }

    /**
     * 해당 월의 마지막 주간 피드백 payload에서 {@code predictedMonthEnd} 값을 읽는다.
     * 없으면 0 반환.
     */
    private long loadPredictedTotal(Long userId, LocalDate monthStart) {
        return feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(userId, "WEEKLY").stream()
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
            MonthEndVerification existing = monthEndVerifRepo
                    .findByUserIdAndYearMonth(userId, monthStart)
                    .orElse(null);
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
            Long userId,
            LocalDate weekStart,
            LocalDate weekEnd,
            LocalDate monthStart,
            FeedbackRenderer.Rendered out,
            Integer topOverCat,
            FeedbackType feedbackType,
            Map<Integer, Long> overspend,
            String payload,
            LocalDateTime now,
            List<UserReaction> reactions,
            boolean isLowData) {

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

        UserProfile profileEntity = userProfileRepo.findByUserId(userId).orElseGet(() -> UserProfile.builder()
                .userId(userId)
                .repeatPatterns("{}")
                .sensitiveAreas("[]")
                .build());
        List<AiFeedback> recentFeedbacks =
                feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(userId, "WEEKLY");
        profileUpdater.update(profileEntity, overspend.keySet(), reactions, recentFeedbacks, isLowData);
        userProfileRepo.save(profileEntity);

        eventPublisher.publishEvent(
                new FeedbackCreatedEvent(userId, YearMonth.from(monthStart).toString()));
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
            long budget = anomalyBudget(catBudget, window, weekEnd, cat);

            for (Expense e : catExpenses) {
                LocalDate d = e.getDate();
                if (d.isBefore(weekStart) || d.isAfter(weekEnd)) continue;

                double[] hist = baselineHistory(catExpenses, d, cat);
                var z = detector.checkPoint(e.getAmount(), hist, budget);
                if (z.isPresent()) {
                    double median = hist.length > 0 ? RobustStats.median(hist) : 0;
                    double mag = hist.length > 0 ? e.getAmount() / Math.max(median, 1) : z.getAsDouble();
                    Anomaly candidate = new Anomaly(cat, e, mag, hist.length, false, e.getAmount(), Math.round(median));
                    bestPerCat.merge(
                            cat,
                            candidate,
                            (existing, newOne) -> newOne.magnitude() > existing.magnitude() ? newOne : existing);
                }
            }
        }

        // 추세: 고빈도 카테고리 — 4+4 대칭 윈도우
        int weeks = p.getBaselineWeeks();
        if (weeks >= TREND_MIN_WEEKS) {
            for (int cat :
                    catBudget.keySet().stream().filter(detector::isHighFreq).toList()) {
                if (detector.isSpikeProne(cat)) continue;

                double[] weekly = weeklySeries(window, cat, weekEnd, weeks);
                if (detector.trendConfirmed(weekly, weeks - 1, anomalyBudget(catBudget, window, weekEnd, cat))) {
                    // 확정 판정과 같은 구간(최근 2주 vs 그 앞 4주)의 배수를 그대로 쓴다 (#138)
                    AnomalyDetector.TrendStats trend = detector.trendStats(weekly, weeks - 1);
                    double rm = trend.recentMean();
                    double pm = trend.priorMean();
                    double mag = trend.ratio();

                    int histLen = (int) byCat.getOrDefault(cat, List.of()).stream()
                            .filter(x -> x.getDate().isBefore(weekStart))
                            .count();

                    List<Expense> catExpenses = byCat.getOrDefault(cat, List.of());
                    Expense rep = catExpenses.stream()
                            .filter(x -> !x.getDate().isBefore(weekStart)
                                    && !x.getDate().isAfter(weekEnd))
                            .max(Comparator.comparing(Expense::getExpenseDate))
                            .orElse(null);
                    if (rep != null) {
                        Anomaly candidate = new Anomaly(cat, rep, mag, histLen, true, Math.round(rm), Math.round(pm));
                        bestPerCat.merge(
                                cat,
                                candidate,
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
            long budget = anomalyBudget(catBudget, window, weekEnd, cat);

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
                .sorted(Comparator.comparingLong(
                                (AnomalyCandidate c) -> c.expense().getAmount())
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

    /**
     * 카테고리별 초과 예상. 전체 예측과 같은 {@link BudgetProjector}로 계산한다 — 이번 달 변동 일평균과 전월 같은 카테고리 일평균을 경과일
     * 비율로 섞는다.
     *
     * <p>이전에는 "이번 달 변동 ÷ 경과일 × 월 일수"로 단순 외삽해, 월초 큰 결제 한 건이 한 달 내내 반복되는 것처럼 커졌다 (#122 실험 0, 35주차:
     * 4일째 기타 +1,621,585원·오락문화 +803,650원 vs 전체 예상 초과 668,463원).
     *
     * <p>단, 혼합식만 쓰면 이번 달에 아직 적게 쓴 카테고리도 지난달 습관만으로 초과 경고가 된다 (실험 5, 18주차 6/7: 기타·식대 — 사용자 판정
     * 틀림). 그래서 두 예측(이번 달 속도만 / 지난달과 혼합) 중 작은 쪽을 쓴다 — 둘 다 예산을 넘어야 초과로 본다.
     */
    private Map<Integer, Long> computeOverspend(
            List<Expense> window,
            Map<Integer, Long> catBudget,
            LocalDate monthStart,
            LocalDate weekEnd,
            int day,
            int dim,
            double[] dowWeights,
            Map<Integer, Long> usualMonthlyOut) {
        Map<Integer, Long> overspend = new HashMap<>();
        for (var entry : catBudget.entrySet()) {
            int cat = entry.getKey();
            // 예산이 배분되지 않은 카테고리(기타 등)는 예산 0원과 비교하면 지출 전액이 초과가 된다 (#141) — 평소(직전 3개월 월평균)와 비교한다
            long budget = entry.getValue();
            if (budget <= 0) {
                budget = usualMonthlyTotal(window, monthStart, cat);
                if (budget <= 0) continue; // 평소를 알 수 없으면 초과를 판정하지 않는다
                usualMonthlyOut.put(cat, budget);
            }
            long varMtd =
                    sum(window, e -> e.getCategoryId() == cat && inMonth(e, monthStart, weekEnd) && e.isVariable());
            long fixMtd = sum(
                    window,
                    e -> e.getCategoryId() == cat
                            && inMonth(e, monthStart, weekEnd)
                            && e.isExpense()
                            && !e.isVariable());
            double priorCatRate = priorVariableDailyRate(window, monthStart, cat);
            long blended =
                    BudgetProjector.predictCategory(varMtd, fixMtd, day, dim, priorCatRate, dowWeights, monthStart);
            long currentPaceOnly = Math.round((double) varMtd / Math.max(day, 1) * dim) + fixMtd;
            long over = Math.min(blended, currentPaceOnly) - budget;
            if (over > 0) overspend.put(cat, over);
        }
        return overspend;
    }

    /**
     * 카테고리별 남은 기간 예상 변동 지출 (고정 지출은 감축 불가이므로 제외). [7] SavingsActionCalculator의 감축가능액 계산에 사용.
     *
     * <p>카테고리 초과({@link #computeOverspend})와 같은 예측식을 쓴다. 이전에는 "이번 달 변동 ÷ 경과일 × 남은 일수"라 월초 큰 결제가
     * 남은 기간 내내 반복되는 것처럼 커져, 부족분 전부를 한 카테고리에서 줄이라는 액션이 나왔다 (#122 실험 0, 35주차: 4일째 기타 668,463원).
     */
    private Map<Integer, Long> buildCategoryRemainingExpected(
            List<Expense> window, LocalDate monthStart, LocalDate weekEnd, int day, int dim, double[] dowWeights) {
        Map<Integer, Long> varMtd = new HashMap<>();
        for (Expense e : window) {
            if (!e.isVariable() || !inMonth(e, monthStart, weekEnd)) continue;
            varMtd.merge(e.getCategoryId(), e.getAmount(), Long::sum);
        }
        Map<Integer, Long> remaining = new HashMap<>();
        varMtd.forEach((cat, mtd) -> {
            double prior = priorVariableDailyRate(window, monthStart, cat);
            remaining.put(cat, BudgetProjector.remainingCategoryExpected(mtd, day, dim, prior, dowWeights, monthStart));
        });
        return remaining;
    }

    /**
     * overallConfidence 파생. bandWidth + 이상치 신뢰도 기반.
     * WIDE→LOW, MEDIUM→MEDIUM, NARROW→HIGH. 이상치 신뢰도와 lower() 적용.
     */
    private Confidence deriveOverallConfidence(PacingResult pacing, Confidence anomalyConfidence, boolean hasAnomaly) {
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

    /** 페이로드 JSON 조립 (버전 포함). */
    private String buildPayload(
            FeedbackType feedbackType,
            PacingResult pacing,
            Map<Integer, Long> overspend,
            List<ActionPlan> actions,
            List<EnrichedAction> enrichedActions,
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
            if (enrichedActions != null && !enrichedActions.isEmpty()) {
                payload.put(
                        "enrichedActions",
                        enrichedActions.stream()
                                .map(a -> {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("categoryId", a.categoryId());
                                    m.put("reductionWon", a.reductionWon());
                                    m.put("dominantFactor", a.dominantFactor());
                                    m.put("feasibilityScore", a.feasibilityScore());
                                    m.put("actionType", a.actionType());
                                    m.put("currentBehavior", a.currentBehavior());
                                    m.put("targetBehavior", a.targetBehavior());
                                    m.put("contextInsight", a.contextInsight());
                                    m.put("behaviorFeasibility", a.behaviorFeasibility());
                                    return m;
                                })
                                .toList());
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("페이로드 직렬화 실패", e);
            return "{}";
        }
    }

    /** 카테고리 주간 합계(변동만), oldest→current, 길이 weeks. 현재 주 = 인덱스 weeks-1. */
    /** 단발 이상치 기준(한 건 중앙값)에 실제 계산 기간을 붙인다 — 문구의 "최근 N주"가 설정값과 어긋나지 않게. */
    private FeedbackRenderer.AnomalyBasis withBaselineWeeks(FeedbackRenderer.AnomalyBasis b) {
        if (b == null || !b.singleTransaction()) return b;
        return new FeedbackRenderer.AnomalyBasis(
                true, b.subjectWon(), b.baselineWon(), b.subjectLabel(), p.getBaselineWeeks());
    }

    /**
     * 이번 주 시작 전 {@code weeks}주의 주당 평균 변동지출. 지출이 있는 주가 {@code minActive}주 미만이면 0 (기록이 짧은 사용자에게 낮은 평균을
     * '평소'로 보여 주지 않기 위해). 지출이 없던 주도 0원으로 평균에 넣는다.
     */
    static long recentWeeklyAverage(List<Expense> window, LocalDate weekStart, int weeks, int minActive) {
        long total = 0;
        int active = 0;
        for (int w = 1; w <= weeks; w++) {
            LocalDate from = weekStart.minusWeeks(w),
                    to = weekStart.minusWeeks(w - 1).minusDays(1);
            long sum = window.stream()
                    .filter(e -> e.isVariable()
                            && !e.getDate().isBefore(from)
                            && !e.getDate().isAfter(to))
                    .mapToLong(Expense::getAmount)
                    .sum();
            if (sum > 0) active++;
            total += sum;
        }
        return active < minActive ? 0 : Math.round((double) total / weeks);
    }

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
    /**
     * 이상치 판정의 최소 금액·추세 기준에 쓰는 카테고리 예산. 예산이 배분되지 않는 카테고리(기타)는 예산이 0원이라 기준이 없어져 1만 원만 넘어도
     * 이상치가 되므로, 직전 3개월 평소 월 지출로 대신한다 (#141). 평소를 알 수 없으면 0.
     */
    public static long anomalyBudget(Map<Integer, Long> catBudget, List<Expense> window, LocalDate weekEnd, int cat) {
        long budget = catBudget.getOrDefault(cat, 0L);
        return budget > 0 ? budget : usualMonthlyTotal(window, weekEnd.withDayOfMonth(1), cat);
    }

    /** 평소 월 지출을 보는 기간 (개월). */
    private static final int USUAL_MONTHS = 3;
    /** 평소를 판단하려면 지출이 있는 달이 이만큼은 있어야 한다. */
    private static final int USUAL_MIN_MONTHS = 2;

    /**
     * 카테고리의 평소 월 지출 — 직전 3개월 중 지출이 있는 달의 월평균(고정 포함). 지출이 있는 달이 {@value #USUAL_MIN_MONTHS}개 미만이면 0.
     * 예산이 배분되지 않는 기타의 초과 기준이다 (#141).
     */
    public static long usualMonthlyTotal(List<Expense> window, LocalDate monthStart, int categoryId) {
        long total = 0;
        int months = 0;
        for (int k = 1; k <= USUAL_MONTHS; k++) {
            LocalDate from = monthStart.minusMonths(k);
            LocalDate to = from.plusMonths(1);
            long sum = window.stream()
                    .filter(e -> e.isExpense()
                            && e.getCategoryId() == categoryId
                            && !e.getDate().isBefore(from)
                            && e.getDate().isBefore(to))
                    .mapToLong(Expense::getAmount)
                    .sum();
            if (sum > 0) {
                total += sum;
                months++;
            }
        }
        return months >= USUAL_MIN_MONTHS ? total / months : 0;
    }

    private double priorVariableDailyRate(List<Expense> window, LocalDate monthStart) {
        return priorVariableDailyRate(window, monthStart, null);
    }

    /** 전월 변동 지출 일평균. categoryId가 null이면 전체, 아니면 해당 카테고리만. */
    private double priorVariableDailyRate(List<Expense> window, LocalDate monthStart, Integer categoryId) {
        LocalDate prevMonth = monthStart.minusMonths(1);
        long sum = window.stream()
                .filter(e -> e.isVariable()
                        && (categoryId == null || categoryId.equals(e.getCategoryId()))
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
            avgByDow[i] = dayCountByDow[i] > 0 ? (double) sumByDow[i] / dayCountByDow[i] : 0.0;
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
                long budget = anomalyBudget(catBudget, window, weekEnd, cat);
                double[] hist = baselineHistory(byCat.getOrDefault(cat, List.of()), d, cat);
                isAnomaly = detector.checkPoint(e.getAmount(), hist, budget).isPresent();
            }

            if (isAnomaly
                    || isHighlightEmotion(e.getEmotion())
                    || (e.getMemo() != null && !e.getMemo().isBlank())) {
                result.add(
                        new TransactionHighlight(d, Categories.name(cat), e.getAmount(), e.getMemo(), e.getEmotion()));
            }
        }

        return TransactionHighlight.groupIdenticalTopN(result, 5);
    }

    /** 큰 한 건이 월말 예상 초과의 이만큼(30%) 이상이고 이 금액 이상이어야 따로 알린다. */
    private static final long LARGE_SINGLE_MIN_WON = 50_000;

    private static final double LARGE_SINGLE_SHARE = 0.3;

    /**
     * 이번 달 이 카테고리의 가장 큰 변동 지출 한 건이 월말 예상 초과의 {@value #LARGE_SINGLE_SHARE}배 이상이면 그 거래, 아니면 null (#141).
     * 이미 쓴 돈이라 '줄이기'가 아니라 다음 달 계획으로 연결하기 위한 재료다.
     */
    static Expense largestSingleOfOverspend(
            List<Expense> window, int cat, LocalDate monthStart, LocalDate weekEnd, long overWon) {
        return window.stream()
                .filter(x -> x.getCategoryId() == cat
                        && x.isVariable()
                        && !x.getDate().isBefore(monthStart)
                        && !x.getDate().isAfter(weekEnd))
                .max(Comparator.comparingLong(Expense::getAmount))
                .filter(x -> x.getAmount() >= LARGE_SINGLE_MIN_WON && x.getAmount() >= overWon * LARGE_SINGLE_SHARE)
                .orElse(null);
    }

    private static TransactionHighlight findWeekLargest(List<Expense> window, LocalDate weekStart, LocalDate weekEnd) {
        return window.stream()
                .filter(e -> e.isVariable()
                        && !e.getDate().isBefore(weekStart)
                        && !e.getDate().isAfter(weekEnd))
                .max(Comparator.comparingLong(Expense::getAmount))
                .map(e -> new TransactionHighlight(
                        e.getDate(), Categories.name(e.getCategoryId()), e.getAmount(), e.getMemo(), e.getEmotion()))
                .orElse(null);
    }

    private static boolean isHighlightEmotion(String emotion) {
        return emotion != null && (emotion.equalsIgnoreCase("REGRET") || emotion.equalsIgnoreCase("IMPULSE"));
    }

    private interface Pred {
        boolean test(Expense e);
    }

    private int count(List<Expense> xs, Pred f) {
        int n = 0;
        for (Expense e : xs) if (f.test(e)) n++;
        return n;
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
    /**
     * @param trend       true면 추세 이상치(최근 2주 vs 그 앞 4주 주당 평균), false면 단발 이상치(한 건 vs 과거 한 건 중앙값)
     * @param subjectWon  배수의 분자 — 단발: 거래 금액 / 추세: 최근 2주 주당 평균
     * @param baselineWon 배수의 분모 — 단발: 과거 한 건 중앙값 / 추세: 그 앞 4주 주당 평균
     */
    private record Anomaly(
            int categoryId,
            Expense rep,
            double magnitude,
            int histLength,
            boolean trend,
            long subjectWon,
            long baselineWon) {

        /** 프롬프트에 배수 기준을 밝히기 위한 정보 (#111). 기준값이 없으면(과거 거래 없음) null — 렌더러가 기존 표기 사용. */
        FeedbackRenderer.AnomalyBasis basis() {
            if (baselineWon <= 0) return null;
            if (trend) return new FeedbackRenderer.AnomalyBasis(false, subjectWon, baselineWon, null);
            String label = rep.getMemo() != null && !rep.getMemo().isBlank() ? rep.getMemo() : rep.getTitle();
            return new FeedbackRenderer.AnomalyBasis(true, subjectWon, baselineWon, label);
        }
    }

    /** [3.5] 메모 질문 큐 적재 후보. */
    private record AnomalyCandidate(Expense expense, double magnitude) {}
}
