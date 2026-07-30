package com.planus.backend.domain.aifeedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.action.ActionPlan;
import com.planus.backend.domain.aifeedback.action.SavingsActionCalculator;
import com.planus.backend.domain.aifeedback.cause.CauseSignalAssembler;
import com.planus.backend.domain.aifeedback.cause.CauseSignals;
import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.*;
import com.planus.backend.domain.aifeedback.entity.*;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.Rendered;
import com.planus.backend.domain.aifeedback.profile.ProfileUpdater;
import com.planus.backend.domain.aifeedback.repository.*;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AiFeedbackServiceTest {

    // ── Mocks ──
    private UserAccountRepository userRepo;
    private ExpenseRepository expenseRepo;
    private BudgetRepository budgetRepo;
    private BudgetCategoryRepository budgetCatRepo;
    private AiFeedbackRepository feedbackRepo;
    private UserProfileRepository userProfileRepo;
    private UserReactionRepository reactionRepo;
    private MemoQuestionQueueRepository memoQueueRepo;
    private AnomalyDetector detector;
    private BudgetProjector projector;
    private FeedbackRenderer renderer;
    private ActivityGuard activityGuard;
    private PacingComparator pacingComparator;
    private FeedbackTypeResolver feedbackTypeResolver;
    private CauseSignalAssembler causeAssembler;
    private SavingsActionCalculator savingsCalculator;
    private ProfileUpdater profileUpdater;
    private TransactionTemplate tx;

    private AiFeedbackService service;

    private static final Long USER_ID = 1L;
    private static final LocalDate WEEK_END = LocalDate.of(2026, 7, 12); // 일요일
    private static final LocalDate WEEK_START = WEEK_END.minusDays(6);
    private static final LocalDate MONTH_START = WEEK_END.withDayOfMonth(1);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(WEEK_END.atTime(12, 0).toInstant(ZoneOffset.of("+09:00")), ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        userRepo = mock(UserAccountRepository.class);
        expenseRepo = mock(ExpenseRepository.class);
        budgetRepo = mock(BudgetRepository.class);
        budgetCatRepo = mock(BudgetCategoryRepository.class);
        feedbackRepo = mock(AiFeedbackRepository.class);
        userProfileRepo = mock(UserProfileRepository.class);
        reactionRepo = mock(UserReactionRepository.class);
        memoQueueRepo = mock(MemoQuestionQueueRepository.class);
        detector = mock(AnomalyDetector.class);
        projector = mock(BudgetProjector.class);
        renderer = mock(FeedbackRenderer.class);
        activityGuard = mock(ActivityGuard.class);
        pacingComparator = mock(PacingComparator.class);
        feedbackTypeResolver = mock(FeedbackTypeResolver.class);
        causeAssembler = mock(CauseSignalAssembler.class);
        savingsCalculator = mock(SavingsActionCalculator.class);
        profileUpdater = mock(ProfileUpdater.class);

        // TransactionTemplate: 즉시 실행
        tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        AiFeedbackProperties props = new AiFeedbackProperties();
        ObjectMapper objectMapper = new ObjectMapper();

        service = new AiFeedbackService(
                userRepo,
                expenseRepo,
                budgetRepo,
                budgetCatRepo,
                feedbackRepo,
                null, // monthEndVerifRepo
                userProfileRepo,
                reactionRepo,
                memoQueueRepo,
                detector,
                projector,
                renderer,
                activityGuard,
                pacingComparator,
                feedbackTypeResolver,
                causeAssembler,
                savingsCalculator,
                profileUpdater,
                props,
                objectMapper,
                FIXED_CLOCK,
                tx);
    }

    // ── 공통 stub ──

    private UserAccount defaultUser() {
        return UserAccount.builder()
                .id(USER_ID)
                .monthlyIncome(3_000_000)
                .monthlyFixedExpenses(1_000_000)
                .monthlySavingsGoal(500_000)
                .build();
    }

    private Budget defaultBudget() {
        return Budget.builder()
                .id(1L)
                .userId(USER_ID)
                .yearMonth(MONTH_START)
                .totalBudget(1_500_000)
                .build();
    }

    private List<BudgetCategory> defaultBudgetCategories() {
        return List.of(
                BudgetCategory.builder()
                        .budgetId(1L)
                        .categoryId(1)
                        .amount(300_000)
                        .build(),
                BudgetCategory.builder()
                        .budgetId(1L)
                        .categoryId(6)
                        .amount(200_000)
                        .build());
    }

    /** 8주 분 변동 지출 fixture. 주당 3건, 카테고리1·6 교대. */
    private List<Expense> defaultWindow() {
        var list = new java.util.ArrayList<Expense>();
        for (int w = 0; w < 8; w++) {
            for (int d = 0; d < 3; d++) {
                int cat = d % 2 == 0 ? 1 : 6;
                list.add(Expense.builder()
                        .id((long) (w * 3 + d))
                        .userId(USER_ID)
                        .categoryId(cat)
                        .type("EXPENSE")
                        .amount(20_000)
                        .expenseDate(WEEK_END.minusWeeks(w).minusDays(d).atTime(12, 0))
                        .recurring(false)
                        .planned(false)
                        .build());
            }
        }
        return list;
    }

    private void stubCommon() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(defaultUser()));
        when(budgetRepo.findByUserIdAndYearMonth(USER_ID, MONTH_START)).thenReturn(Optional.of(defaultBudget()));
        when(budgetCatRepo.findByBudgetId(1L)).thenReturn(defaultBudgetCategories());
        when(expenseRepo.findByUserIdAndExpenseDateBetween(eq(USER_ID), any(), any()))
                .thenReturn(defaultWindow());
        when(reactionRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(userProfileRepo.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(feedbackRepo.findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(eq(USER_ID), eq("WEEKLY"), any(), any()))
                .thenReturn(Optional.empty());
        when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(USER_ID, "WEEKLY"))
                .thenReturn(List.of());
        when(feedbackRepo.save(any(AiFeedback.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userProfileRepo.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(memoQueueRepo.countPendingForWeek(eq(USER_ID), any())).thenReturn(0L);
    }

    private void stubNormalActivity() {
        when(activityGuard.normalWeeklyCount(any())).thenReturn(3.0);
        when(activityGuard.isLowData(anyLong(), anyDouble())).thenReturn(false);
    }

    private void stubProjection(long predictedMonthEnd, long savingsImpact) {
        when(projector.project(anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new BudgetProjector.Projection(predictedMonthEnd, 500_000, 20_000));
        when(projector.savingsImpact(predictedMonthEnd, 1_500_000)).thenReturn(savingsImpact);
    }

    private void stubPositive() {
        stubProjection(1_200_000, 300_000);
        when(feedbackTypeResolver.resolve(eq(false), eq(300_000L), eq(false), anyBoolean()))
                .thenReturn(FeedbackType.POSITIVE);
        when(renderer.render(any())).thenReturn(new Rendered("좋은 소비 습관이에요.", Confidence.HIGH));
    }

    // ── 테스트 ──

    @Test
    @DisplayName("사용자 없으면 빈 Optional")
    void noBudget_empty() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.empty());

        Optional<AiFeedback> result = service.generateWeekly(USER_ID, WEEK_END);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("예산 없으면 빈 Optional")
    void generateWeekly_noBudget_empty() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(defaultUser()));
        when(budgetRepo.findByUserIdAndYearMonth(USER_ID, MONTH_START)).thenReturn(Optional.of(defaultBudget()));
        when(budgetCatRepo.findByBudgetId(1L)).thenReturn(List.of()); // 빈 카테고리 예산 → 빈 맵

        Optional<AiFeedback> result = service.generateWeekly(USER_ID, WEEK_END);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("LOW_DATA → [2]~[7] 스킵, LOW_DATA로 저장")
    void generateWeekly_lowData_skipsAnalysis() {
        stubCommon();
        when(activityGuard.normalWeeklyCount(any())).thenReturn(10.0);
        when(activityGuard.isLowData(anyLong(), eq(10.0))).thenReturn(true);
        when(renderer.render(any())).thenReturn(new Rendered("기록 부족", Confidence.LOW));

        Optional<AiFeedback> result = service.generateWeekly(USER_ID, WEEK_END);

        assertThat(result).isPresent();
        AiFeedback fb = result.get();
        assertThat(fb.getFeedbackType()).isEqualTo("LOW_DATA");
        assertThat(fb.isHadOverspend()).isFalse();

        // [2]~[7] 스킵 확인: 분석 컴포넌트 호출 안 됨
        verifyNoInteractions(pacingComparator);
        verifyNoInteractions(detector);
        verifyNoInteractions(projector);
        verifyNoInteractions(feedbackTypeResolver);
        verifyNoInteractions(savingsCalculator);
        verifyNoInteractions(causeAssembler);
        verifyNoInteractions(memoQueueRepo);
    }

    @Test
    @DisplayName("POSITIVE 피드백 정상 생성")
    void generateWeekly_positive() {
        stubCommon();
        stubNormalActivity();
        stubPositive();

        Optional<AiFeedback> result = service.generateWeekly(USER_ID, WEEK_END);

        assertThat(result).isPresent();
        AiFeedback fb = result.get();
        assertThat(fb.getFeedbackType()).isEqualTo("POSITIVE");
        assertThat(fb.getFeedbackText()).contains("좋은 소비 습관");
        assertThat(fb.isHadOverspend()).isFalse();
    }

    @Test
    @DisplayName("ALERT + 절약 액션 생성")
    void generateWeekly_alert_withActions() {
        stubCommon();
        stubNormalActivity();
        stubProjection(1_800_000, -300_000);
        when(feedbackTypeResolver.resolve(eq(false), eq(-300_000L), eq(false), anyBoolean()))
                .thenReturn(FeedbackType.ALERT);
        when(causeAssembler.assemble(anyInt(), any(), any(), any(), any()))
                .thenReturn(new CauseSignals(null, null, 0, 0, Map.of(), Map.of(), null, List.of(), false));
        when(savingsCalculator.calculate(any(), anyLong(), anyInt(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new ActionPlan(1, 50_000, "FREQUENCY", 0.8)));
        when(renderer.render(any())).thenReturn(new Rendered("절약이 필요해요.", Confidence.HIGH));

        Optional<AiFeedback> result = service.generateWeekly(USER_ID, WEEK_END);

        assertThat(result).isPresent();
        assertThat(result.get().getFeedbackType()).isEqualTo("ALERT");
        verify(savingsCalculator).calculate(any(), anyLong(), anyInt(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("기존 피드백 있으면 update() 호출")
    void generateWeekly_upsert_existingFeedback() {
        stubCommon();
        stubNormalActivity();
        stubPositive();

        AiFeedback existing = AiFeedback.builder()
                .id(99L)
                .userId(USER_ID)
                .periodType("WEEKLY")
                .periodStart(WEEK_START)
                .periodEnd(WEEK_END)
                .feedbackText("old text")
                .confidence("LOW")
                .feedbackType("POSITIVE")
                .createdAt(LocalDateTime.now())
                .build();
        when(feedbackRepo.findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(USER_ID, "WEEKLY", WEEK_START, WEEK_END))
                .thenReturn(Optional.of(existing));

        service.generateWeekly(USER_ID, WEEK_END);

        // update()가 호출되면 feedbackText, payload, 버전이 모두 갱신됨
        assertThat(existing.getFeedbackText()).isEqualTo("좋은 소비 습관이에요.");
        assertThat(existing.getPayload()).isNotNull();
        assertThat(existing.getPromptVersion()).isEqualTo("v2.0");
        assertThat(existing.getLogicVersion()).isEqualTo("v2.0");
        verify(feedbackRepo).save(existing);
    }

    @Test
    @DisplayName("프로필이 없으면 신규 생성 후 save")
    void generateWeekly_profileCreatedIfMissing() {
        stubCommon();
        stubNormalActivity();
        stubPositive();

        Optional<AiFeedback> result = service.generateWeekly(USER_ID, WEEK_END);

        assertThat(result).isPresent();
        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepo).save(captor.capture());
        UserProfile savedProfile = captor.getValue();
        assertThat(savedProfile.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("버전 정보(promptVersion, logicVersion)가 저장된다")
    void generateWeekly_versionRecorded() {
        stubCommon();
        stubNormalActivity();
        stubPositive();

        Optional<AiFeedback> result = service.generateWeekly(USER_ID, WEEK_END);

        assertThat(result).isPresent();
        AiFeedback fb = result.get();
        assertThat(fb.getPromptVersion()).isEqualTo("v2.0");
        assertThat(fb.getLogicVersion()).isEqualTo("v2.0");
    }

    @Test
    @DisplayName("LOW_DATA → ProfileUpdater에 isLowData=true 전달 (streak 보호)")
    void generateWeekly_lowData_profileUpdaterReceivesFlag() {
        stubCommon();
        when(activityGuard.normalWeeklyCount(any())).thenReturn(10.0);
        when(activityGuard.isLowData(anyLong(), eq(10.0))).thenReturn(true);
        when(renderer.render(any())).thenReturn(new Rendered("기록 부족", Confidence.LOW));

        service.generateWeekly(USER_ID, WEEK_END);

        // isLowData=true가 profileUpdater에 전달되었는지 확인
        verify(profileUpdater).update(any(), any(), any(), any(), eq(true));
    }

    @Test
    @DisplayName("정상 흐름 → ProfileUpdater에 isLowData=false 전달")
    void generateWeekly_normal_profileUpdaterReceivesFalse() {
        stubCommon();
        stubNormalActivity();
        stubPositive();

        service.generateWeekly(USER_ID, WEEK_END);

        verify(profileUpdater).update(any(), any(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("페이로드에 version 포함 — JSON 구조 검증")
    void generateWeekly_payloadContainsVersion() throws Exception {
        stubCommon();
        stubNormalActivity();
        stubPositive();

        Optional<AiFeedback> result = service.generateWeekly(USER_ID, WEEK_END);

        assertThat(result).isPresent();
        String payload = result.get().getPayload();
        var tree = new ObjectMapper().readTree(payload);
        assertThat(tree.path("version").path("prompt").asText()).isEqualTo("v2.0");
        assertThat(tree.path("version").path("logic").asText()).isEqualTo("v2.0");
    }

    @Test
    @DisplayName("트랜잭션 내 실행 — tx.execute() 호출 확인")
    void generateWeekly_executesInTransaction() {
        stubCommon();
        stubNormalActivity();
        stubPositive();

        service.generateWeekly(USER_ID, WEEK_END);

        verify(tx).execute(any());
    }

    @Test
    @DisplayName("저장이 트랜잭션 콜백 내부에서만 실행된다")
    void generateWeekly_savesOnlyInsideTransaction() {
        stubCommon();
        stubNormalActivity();
        stubPositive();

        // tx.execute()가 콜백을 실행하지 않도록 재설정
        reset(tx);
        when(tx.execute(any())).thenReturn(null);

        service.generateWeekly(USER_ID, WEEK_END);

        // 콜백 미실행 → 저장이 호출되지 않아야 함
        verify(feedbackRepo, never()).save(any(AiFeedback.class));
        verify(userProfileRepo, never()).save(any(UserProfile.class));
    }
}
