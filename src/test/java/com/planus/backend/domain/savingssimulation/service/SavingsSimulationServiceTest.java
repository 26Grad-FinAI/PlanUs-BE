package com.planus.backend.domain.savingssimulation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.repository.AiFeedbackRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.savingssimulation.dto.*;
import com.planus.backend.domain.savingssimulation.dto.SimulateResponse.DisplayType;
import com.planus.backend.domain.savingssimulation.entity.SavingSimulation;
import com.planus.backend.domain.savingssimulation.repository.SavingSimulationRepository;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SavingsSimulationServiceTest {

    private AiFeedbackRepository feedbackRepo;
    private BudgetRepository budgetRepo;
    private BudgetCategoryRepository budgetCatRepo;
    private ExpenseRepository expenseRepo;
    private UserAccountRepository userRepo;
    private SavingSimulationRepository simulationRepo;
    private ObjectMapper objectMapper;

    private SavingsSimulationService service;

    @BeforeEach
    void setUp() {
        feedbackRepo = mock(AiFeedbackRepository.class);
        budgetRepo = mock(BudgetRepository.class);
        budgetCatRepo = mock(BudgetCategoryRepository.class);
        expenseRepo = mock(ExpenseRepository.class);
        userRepo = mock(UserAccountRepository.class);
        simulationRepo = mock(SavingSimulationRepository.class);
        objectMapper = new ObjectMapper();

        // 기본: 12월 5일 — 11월은 지난 달이라 실제 합계를 쓴다
        service = new SavingsSimulationService(
                feedbackRepo,
                budgetRepo,
                budgetCatRepo,
                expenseRepo,
                userRepo,
                simulationRepo,
                mock(com.planus.backend.domain.aifeedback.repository.UserReactionRepository.class),
                new com.planus.backend.domain.aifeedback.config.AiFeedbackProperties(),
                mock(com.planus.backend.domain.aifeedback.service.AiFeedbackService.class),
                objectMapper,
                fixedClock(LocalDate.of(2026, 12, 5)));
    }

    // ── 공통 헬퍼 ──

    private static com.planus.backend.domain.aifeedback.entity.Expense variable(
            int categoryId, long amount, LocalDate date) {
        return com.planus.backend.domain.aifeedback.entity.Expense.builder()
                .userId(1L)
                .categoryId(categoryId)
                .type("EXPENSE")
                .amount(amount)
                .expenseDate(date.atTime(12, 0))
                .build();
    }

    private static java.time.Clock fixedClock(LocalDate date) {
        return java.time.Clock.fixed(
                date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault());
    }

    private String buildPayloadWithEnrichedActions() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", Map.of("prompt", "v2.1", "logic", "v2.1"));
        payload.put("feedbackType", "ALERT");
        payload.put(
                "enrichedActions",
                List.of(
                        Map.ofEntries(
                                Map.entry("categoryId", 7),
                                Map.entry("reductionWon", 40000),
                                Map.entry("dominantFactor", "FREQUENCY"),
                                Map.entry("feasibilityScore", 0.74),
                                Map.entry("actionType", "REDUCE_FREQUENCY"),
                                Map.entry("currentBehavior", "주 4.2회 외식"),
                                Map.entry("targetBehavior", "주 2회로 감소"),
                                Map.entry("contextInsight", "금요일 저녁 외식이 전체의 60%"),
                                Map.entry("behaviorFeasibility", 0.74)),
                        Map.ofEntries(
                                Map.entry("categoryId", 5),
                                Map.entry("reductionWon", 20000),
                                Map.entry("dominantFactor", "UNIT_PRICE"),
                                Map.entry("feasibilityScore", 0.65),
                                Map.entry("actionType", "REDUCE_UNIT_PRICE"),
                                Map.entry("currentBehavior", "건당 평균 25,000원"),
                                Map.entry("targetBehavior", "건당 15,000원 이하"),
                                Map.entry("contextInsight", "주말 오락 지출이 80%"),
                                Map.entry("behaviorFeasibility", 0.65))));
        return objectMapper.writeValueAsString(payload);
    }

    private String buildPayloadWithActionsOnly() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("feedbackType", "ALERT");
        payload.put(
                "actions",
                List.of(
                        Map.of(
                                "categoryId",
                                7,
                                "reductionWon",
                                40000,
                                "dominantFactor",
                                "FREQUENCY",
                                "feasibilityScore",
                                0.74),
                        Map.of(
                                "categoryId",
                                5,
                                "reductionWon",
                                5000,
                                "dominantFactor",
                                "BOTH",
                                "feasibilityScore",
                                0.50)));
        return objectMapper.writeValueAsString(payload);
    }

    private AiFeedback feedbackWith(String payload) {
        return AiFeedback.builder()
                .id(1L)
                .userId(1L)
                .yearMonth(LocalDate.of(2026, 11, 1))
                .periodType("WEEKLY")
                .periodStart(LocalDate.of(2026, 11, 3))
                .periodEnd(LocalDate.of(2026, 11, 9))
                .payload(payload)
                .build();
    }

    private UserAccount userWithGoal(long income, long fixedTotal, long savingsGoal) {
        return UserAccount.builder()
                .id(1L)
                .email("test@example.com")
                .monthlyIncome(income)
                .monthlySavingsGoal(savingsGoal)
                .build();
    }

    // ── suggest() ──

    @Nested
    @DisplayName("suggest()")
    class Suggest {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("진행 중인 달은 오늘 기준으로 다시 계산해 피드백과 비교한다 — 이어진 카테고리 FEEDBACK, 새로 생긴 카테고리 NEW, 사라진 카테고리 제외 (#127)")
            void recomputesAsOfToday() throws Exception {
                var feedbackService = mock(com.planus.backend.domain.aifeedback.service.AiFeedbackService.class);
                service = new SavingsSimulationService(
                        feedbackRepo,
                        budgetRepo,
                        budgetCatRepo,
                        expenseRepo,
                        userRepo,
                        simulationRepo,
                        mock(com.planus.backend.domain.aifeedback.repository.UserReactionRepository.class),
                        new com.planus.backend.domain.aifeedback.config.AiFeedbackProperties(),
                        feedbackService,
                        objectMapper,
                        fixedClock(LocalDate.of(2026, 11, 16)));
                // 피드백(11/9): 식대(7) 4만 원·오락(5) 2만 원 제안, 저축 목표까지 부족액 290,000원
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = objectMapper.readValue(buildPayloadWithEnrichedActions(), Map.class);
                payload.put("savingsGapWon", 290_000);
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(feedbackWith(objectMapper.writeValueAsString(payload))));
                // 오늘(11/16): 식대는 5만 원으로 늘고, 오락은 줄일 필요가 없어졌고, 쇼핑(3)이 새로 초과 예상, 부족액 330,000원
                when(feedbackService.computeActionsAsOf(1L, LocalDate.of(2026, 11, 16)))
                        .thenReturn(
                                new com.planus.backend.domain.aifeedback.service.AiFeedbackService.SavingsActionsAsOf(
                                        List.of(
                                                new com.planus.backend.domain.aifeedback.action.ActionPlan(
                                                        7, 50_000, "FREQUENCY", 0.7),
                                                new com.planus.backend.domain.aifeedback.action.ActionPlan(
                                                        3, 30_000, "UNIT_PRICE", 0.6)),
                                        330_000));

                SuggestResponse result = service.suggest(1L, "2026-11");

                assertThat(result.actions())
                        .extracting(SuggestResponse.ActionCard::categoryId)
                        .containsExactly(7, 3);
                SuggestResponse.ActionCard kept = result.actions().get(0);
                assertThat(kept.source()).isEqualTo("FEEDBACK");
                assertThat(kept.reductionWon()).isEqualTo(50_000);
                assertThat(kept.feedbackReductionWon()).isEqualTo(40_000L);
                assertThat(kept.currentBehavior()).as("문구는 피드백에 저장된 것을 잇는다").isEqualTo("주 4.2회 외식");
                SuggestResponse.ActionCard fresh = result.actions().get(1);
                assertThat(fresh.source()).isEqualTo("NEW");
                assertThat(fresh.feedbackReductionWon()).isNull();
                assertThat(fresh.currentBehavior()).as("LLM을 새로 부르지 않아 문구가 없다").isNull();
                assertThat(result.totalPossibleReduction()).isEqualTo(80_000);
                assertThat(result.comparison()).isEqualTo("11/9 피드백 이후 저축 목표까지 부족액 29만 원 → 33만 원");
            }

            @Test
            @DisplayName("지난 달은 다시 계산하지 않고 피드백에 저장된 액션을 쓴다")
            void pastMonth_usesSavedActions() throws Exception {
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(feedbackWith(buildPayloadWithEnrichedActions())));

                SuggestResponse result = service.suggest(1L, "2026-11"); // 기본 시계는 12월

                assertThat(result.actions()).hasSize(2);
                assertThat(result.actions()).allMatch(a -> "FEEDBACK".equals(a.source()));
                assertThat(result.actions().get(0).feedbackReductionWon()).isEqualTo(40_000L);
                assertThat(result.comparison()).isNull();
            }

            @Test
            @DisplayName("enrichedActions가 있으면 행동 기반 제안을 반환한다")
            void returnsEnrichedActions() throws Exception {
                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(feedbackWith(payload)));

                SuggestResponse result = service.suggest(1L, "2026-11");

                assertThat(result.actions()).hasSize(2);
                assertThat(result.feedbackId()).isEqualTo(1L);
                assertThat(result.applied()).isFalse();
                assertThat(result.totalPossibleReduction()).isEqualTo(60000);

                SuggestResponse.ActionCard first = result.actions().get(0);
                assertThat(first.categoryId()).isEqualTo(7);
                assertThat(first.categoryName()).isEqualTo("식대");
                assertThat(first.actionType()).isEqualTo("REDUCE_FREQUENCY");
                assertThat(first.currentBehavior()).isEqualTo("주 4.2회 외식");
                assertThat(first.targetBehavior()).isEqualTo("주 2회로 감소");
                assertThat(first.contextInsight()).isEqualTo("금요일 저녁 외식이 전체의 60%");
            }

            @Test
            @DisplayName("이미 반영한 피드백도 제안 목록과 적용 여부를 함께 반환한다")
            void returnsAppliedStateWithoutHidingActions() throws Exception {
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(feedbackWith(buildPayloadWithEnrichedActions())));
                when(simulationRepo.existsByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 1L))
                        .thenReturn(true);

                SuggestResponse result = service.suggest(1L, "2026-11");

                assertThat(result.feedbackId()).isEqualTo(1L);
                assertThat(result.applied()).isTrue();
                assertThat(result.actions()).hasSize(2);
            }

            @Test
            @DisplayName("같은 제안의 새 주간 피드백은 이전 주의 적용 여부와 구분한다")
            void newFeedbackHasIndependentAppliedState() throws Exception {
                String payload = buildPayloadWithEnrichedActions();
                AiFeedback previous = feedbackWith(payload);
                AiFeedback next = AiFeedback.builder()
                        .id(2L)
                        .userId(1L)
                        .yearMonth(LocalDate.of(2026, 11, 1))
                        .periodType("WEEKLY")
                        .periodStart(LocalDate.of(2026, 11, 10))
                        .payload(payload)
                        .build();
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(previous))
                        .thenReturn(List.of(next, previous));
                when(simulationRepo.existsByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 1L))
                        .thenReturn(true);

                assertThat(service.suggest(1L, "2026-11").applied()).isTrue();
                SuggestResponse result = service.suggest(1L, "2026-11");

                assertThat(result.feedbackId()).isEqualTo(2L);
                assertThat(result.applied()).isFalse();
                verify(simulationRepo).existsByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 2L);
            }

            @Test
            @DisplayName("enrichedActions가 없으면 기존 actions를 fallback으로 반환한다")
            void fallbackToActions() throws Exception {
                String payload = buildPayloadWithActionsOnly();
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(feedbackWith(payload)));

                SuggestResponse result = service.suggest(1L, "2026-11");

                // reductionWon >= 1_000 필터: categoryId=7(40000), categoryId=5(5000) 모두 통과
                assertThat(result.actions()).hasSize(2);
                assertThat(result.actions().get(0).categoryId()).isEqualTo(7);
                assertThat(result.actions().get(0).actionType()).isNull(); // fallback이므로 null
                assertThat(result.actions().get(1).categoryId()).isEqualTo(5);
                assertThat(result.actions().get(1).actionType()).isNull();
            }

            @Test
            @DisplayName("feasibilityScore 내림차순 정렬")
            void sortedByFeasibility() throws Exception {
                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(feedbackWith(payload)));

                SuggestResponse result = service.suggest(1L, "2026-11");

                assertThat(result.actions().get(0).feasibilityScore())
                        .isGreaterThanOrEqualTo(result.actions().get(1).feasibilityScore());
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("해당 월 피드백이 없으면 FEEDBACK_NOT_FOUND 예외")
            void feedbackNotFound() {
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of());

                assertThatThrownBy(() -> service.suggest(1L, "2026-11"))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.FEEDBACK_NOT_FOUND));
            }

            @Test
            @DisplayName("payload가 비어있으면 NO_ENRICHED_ACTIONS 예외")
            void emptyPayload() {
                AiFeedback fb = feedbackWith(null);
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(fb));

                assertThatThrownBy(() -> service.suggest(1L, "2026-11"))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.NO_ENRICHED_ACTIONS));
            }

            @Test
            @DisplayName("payload에 actions도 enrichedActions도 없으면 NO_ENRICHED_ACTIONS 예외")
            void noActionsInPayload() throws Exception {
                String payload = objectMapper.writeValueAsString(Map.of("feedbackType", "POSITIVE"));
                AiFeedback fb = feedbackWith(payload);
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(fb));

                assertThatThrownBy(() -> service.suggest(1L, "2026-11"))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.NO_ENRICHED_ACTIONS));
            }
        }
    }

    // ── simulate() ──

    @Nested
    @DisplayName("simulate()")
    class Simulate {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("적자 상태에서 절약 시 균형 도달 메시지")
            void deficit_toBalance() throws Exception {
                // 저축액 = 수입 3,000,000 − 고정 0 − 변동 3,050,000 = −50,000 (가용예산 2,500,000은 이미 목표를 뺀 값)
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                // 변동지출 > 수입 − 고정지출 → 적자
                when(expenseRepo.sumVariableExpensesByPeriod(eq(1L), any(), any()))
                        .thenReturn(3_050_000L);

                SimulateRequest request = new SimulateRequest("2026-11", 1L, List.of(7, 5));
                SimulateResponse result = service.simulate(1L, request);

                assertThat(result.displayType()).isEqualTo(DisplayType.DEFICIT);
                assertThat(result.currentMonthlySavings()).isEqualTo(-50_000); // 3,000,000 - 3,050,000
                assertThat(result.projectedMonthlySavings()).isEqualTo(10_000); // -50,000 + 60,000
                assertThat(result.reductionTotal()).isEqualTo(60_000);
                assertThat(result.selectedActions()).hasSize(2);
            }

            @Test
            @DisplayName("저축 목표 미달 → 달성 가능 메시지")
            void recover() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                // 저축 = 3,000,000 - 소비 2,560,000 = 440,000 < 목표 500,000
                when(expenseRepo.sumVariableExpensesByPeriod(eq(1L), any(), any()))
                        .thenReturn(2_560_000L);

                SimulateRequest request = new SimulateRequest("2026-11", 1L, List.of(7, 5));
                SimulateResponse result = service.simulate(1L, request);

                assertThat(result.displayType()).isEqualTo(DisplayType.RECOVER);
                assertThat(result.currentMonthlySavings()).isEqualTo(440_000);
                assertThat(result.projectedMonthlySavings()).isEqualTo(500_000);
            }

            @Test
            @DisplayName("저축 목표 있고 이미 충분 → GOAL 메시지")
            void goal() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                // 저축 = 3,000,000 - 소비 2,400,000 = 600,000 > 목표 500,000
                when(expenseRepo.sumVariableExpensesByPeriod(eq(1L), any(), any()))
                        .thenReturn(2_400_000L);

                SimulateRequest request = new SimulateRequest("2026-11", 1L, List.of(7, 5));
                SimulateResponse result = service.simulate(1L, request);

                assertThat(result.displayType()).isEqualTo(DisplayType.GOAL);
                assertThat(result.reductionTotal()).isEqualTo(60_000);
            }

            @Test
            @DisplayName("저축 목표 없고 여유 → SURPLUS 메시지")
            void surplus() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 0);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                when(expenseRepo.sumVariableExpensesByPeriod(eq(1L), any(), any()))
                        .thenReturn(2_000_000L);

                SimulateRequest request = new SimulateRequest("2026-11", 1L, List.of(7));
                SimulateResponse result = service.simulate(1L, request);

                assertThat(result.displayType()).isEqualTo(DisplayType.SURPLUS);
                assertThat(result.reductionTotal()).isEqualTo(40_000);
                // 카테고리 거래 없음 → 월 절약액 0 (월 기준이 없음)
                assertThat(result.monthlySaving()).isZero();
            }

            @Test
            @DisplayName("월 절약액 = 남은 기간 감축 비율 × 직전 3개월 월평균, 연 = 월 × 12 (#127)")
            void monthlyAndAnnualSaving() throws Exception {
                service = new SavingsSimulationService(
                        feedbackRepo,
                        budgetRepo,
                        budgetCatRepo,
                        expenseRepo,
                        userRepo,
                        simulationRepo,
                        mock(com.planus.backend.domain.aifeedback.repository.UserReactionRepository.class),
                        new com.planus.backend.domain.aifeedback.config.AiFeedbackProperties(),
                        mock(com.planus.backend.domain.aifeedback.service.AiFeedbackService.class),
                        objectMapper,
                        fixedClock(LocalDate.of(2026, 11, 16)));
                UserAccount user = userWithGoal(3_000_000, 0, 0);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));
                when(feedbackRepo.findById(1L))
                        .thenReturn(Optional.of(feedbackWith(buildPayloadWithEnrichedActions())));
                when(expenseRepo.sumVariableExpensesByPeriod(eq(1L), any(), any()))
                        .thenReturn(1_000_000L);
                // 식대(7): 8·9·10월 각 300,000원, 11월 1~16일 160,000원 (일평균 10,000)
                when(expenseRepo.findByUserIdAndCategoryIdAndExpenseDateBetween(eq(1L), eq(7), any(), any()))
                        .thenReturn(List.of(
                                variable(7, 300_000, LocalDate.of(2026, 8, 10)),
                                variable(7, 300_000, LocalDate.of(2026, 9, 10)),
                                variable(7, 300_000, LocalDate.of(2026, 10, 10)),
                                variable(7, 160_000, LocalDate.of(2026, 11, 5))));

                SimulateResponse result = service.simulate(1L, new SimulateRequest("2026-11", 1L, List.of(7)));

                // 남은 14일 예상 = min(혼합, 이번 달 속도만 10,000 × 14 = 140,000). 혼합 일평균 = (16/30)·10,000 + (14/30)·9,677 ≈ 9,849
                // → 남은 기간 ≈ 137,892원. 감축 40,000 ÷ 137,892 ≈ 29% → 월 ≈ 0.29 × 300,000 ≈ 87,000원
                SuggestResponse.ActionCard card = result.selectedActions().getFirst();
                assertThat(card.reductionWon()).isEqualTo(40_000);
                assertThat(card.monthlySavingWon()).isBetween(86_000L, 88_000L);
                assertThat(card.annualSavingWon()).isEqualTo(card.monthlySavingWon() * 12);
                assertThat(result.annualSaving()).isEqualTo(result.monthlySaving() * 12);
                assertThat(result.message())
                        .isEqualTo(String.format(
                                "월 %,d원, 1년 %,d원을 더 저축할 수 있습니다", result.monthlySaving(), result.annualSaving()));
                // 방법·실현 가능성은 거래 데이터에서 코드로 계산한다 (#127): 11/1~16 거래 1건 → 남은 14일 예상 횟수·감축 횟수가 숫자로 나온다
                assertThat(card.method()).startsWith("남은 14일간").contains("번");
                assertThat(card.feasibilityLevel()).isIn("EASY", "NORMAL", "HARD");
                assertThat(card.feasibilityReasons()).isNotEmpty();
                assertThat(card.feasibilityReasons().get(0)).contains("남은 기간 예상 지출");
            }

            @Test
            @DisplayName("저축 목표를 두 번 빼지 않는다 — 목표를 채우고 남으면 GOAL (#127)")
            void savingsGoal_notSubtractedTwice() throws Exception {
                // 수입 2,000,000, 목표 1,000,000 → 가용예산 1,000,000. 변동 800,000 → 저축 1,200,000 ≥ 목표
                // 이전: 가용예산 − 소비 = 200,000 < 목표 → "저축 목표 미달"(RECOVER)로 잘못 분류
                UserAccount user = userWithGoal(2_000_000, 0, 1_000_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));
                when(feedbackRepo.findById(1L))
                        .thenReturn(Optional.of(feedbackWith(buildPayloadWithEnrichedActions())));
                when(expenseRepo.sumVariableExpensesByPeriod(eq(1L), any(), any()))
                        .thenReturn(800_000L);

                SimulateResponse result = service.simulate(1L, new SimulateRequest("2026-11", 1L, List.of(7)));

                assertThat(result.displayType()).isEqualTo(DisplayType.GOAL);
                assertThat(result.currentMonthlySavings()).isEqualTo(1_200_000);
            }

            @Test
            @DisplayName("진행 중인 달은 지금까지 쓴 돈이 아니라 월말 예측으로 계산 (#127)")
            void currentMonth_usesMonthEndProjection() throws Exception {
                service = new SavingsSimulationService(
                        feedbackRepo,
                        budgetRepo,
                        budgetCatRepo,
                        expenseRepo,
                        userRepo,
                        simulationRepo,
                        mock(com.planus.backend.domain.aifeedback.repository.UserReactionRepository.class),
                        new com.planus.backend.domain.aifeedback.config.AiFeedbackProperties(),
                        mock(com.planus.backend.domain.aifeedback.service.AiFeedbackService.class),
                        objectMapper,
                        fixedClock(LocalDate.of(2026, 11, 4)));
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));
                when(feedbackRepo.findById(1L))
                        .thenReturn(Optional.of(feedbackWith(buildPayloadWithEnrichedActions())));
                // 11/1~11/4 변동 400,000 (일평균 100,000), 10월 변동 900,000 (일평균 29,032)
                when(expenseRepo.sumVariableExpensesByPeriod(
                                eq(1L), eq(LocalDate.of(2026, 11, 1).atStartOfDay()), any()))
                        .thenReturn(400_000L);
                when(expenseRepo.sumVariableExpensesByPeriod(
                                eq(1L), eq(LocalDate.of(2026, 10, 1).atStartOfDay()), any()))
                        .thenReturn(900_000L);

                SimulateResponse result = service.simulate(1L, new SimulateRequest("2026-11", 1L, List.of(7)));

                // 혼합 일평균 = (4/30)·100,000 + (26/30)·29,032 ≈ 38,495 → 월말 예측 ≈ 400,000 + 38,495 × 26 ≈ 1,400,860
                // 저축 ≈ 3,000,000 − 1,400,860 ≈ 1,599,140 (지금까지 쓴 돈만 쓰면 2,600,000으로 과대)
                assertThat(result.currentMonthlySavings()).isBetween(1_598_000L, 1_600_500L);
                assertThat(result.displayType()).isEqualTo(DisplayType.GOAL);
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("존재하지 않는 카테고리 선택 시 INVALID_CATEGORY_SELECTION 예외")
            void invalidCategory() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                SimulateRequest request = new SimulateRequest("2026-11", 1L, List.of(99));

                assertThatThrownBy(() -> service.simulate(1L, request))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.INVALID_CATEGORY_SELECTION));
            }

            @Test
            @DisplayName("사용자를 찾을 수 없으면 NOT_FOUND 예외")
            void userNotFound() {
                when(userRepo.findById(1L)).thenReturn(Optional.empty());

                SimulateRequest request = new SimulateRequest("2026-11", 1L, List.of(7));

                assertThatThrownBy(() -> service.simulate(1L, request)).isInstanceOf(GeneralException.class);
            }
        }
    }

    // ── apply() ──

    @Nested
    @DisplayName("apply()")
    class Apply {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("새 피드백이 생성돼도 조회한 피드백으로 시뮬레이션하고 예산을 반영한다")
            void appliesReduction() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                when(simulationRepo.findByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 1L))
                        .thenReturn(Optional.empty());

                Budget budget = Budget.builder()
                        .id(10L)
                        .userId(1L)
                        .yearMonth(LocalDate.of(2026, 11, 1))
                        .totalBudget(1_600_000)
                        .build();
                when(budgetRepo.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 11, 1)))
                        .thenReturn(Optional.of(budget));

                BudgetCategory cat7 = BudgetCategory.builder()
                        .budgetId(10L)
                        .categoryId(7)
                        .amount(200_000)
                        .build();
                BudgetCategory cat5 = BudgetCategory.builder()
                        .budgetId(10L)
                        .categoryId(5)
                        .amount(100_000)
                        .build();
                when(budgetCatRepo.findByBudgetId(10L)).thenReturn(List.of(cat7, cat5));

                when(simulationRepo.save(any(SavingSimulation.class))).thenAnswer(inv -> inv.getArgument(0));

                // 조회 후 새 피드백이 생겨도 적용 요청의 피드백을 사용한다.
                AiFeedback newer = AiFeedback.builder()
                        .id(2L)
                        .userId(1L)
                        .yearMonth(LocalDate.of(2026, 11, 1))
                        .periodType("WEEKLY")
                        .periodStart(LocalDate.of(2026, 11, 10))
                        .payload(payload.replace("40000", "10000"))
                        .build();
                when(feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(1L, "WEEKLY"))
                        .thenReturn(List.of(feedbackWith(payload)))
                        .thenReturn(List.of(newer));
                SuggestResponse viewed = service.suggest(1L, "2026-11");
                assertThat(service.suggest(1L, "2026-11").feedbackId()).isEqualTo(2L);

                SimulateResponse simulated =
                        service.simulate(1L, new SimulateRequest("2026-11", viewed.feedbackId(), List.of(7, 5)));
                assertThat(simulated.reductionTotal()).isEqualTo(60000);
                verifyNoInteractions(budgetRepo, budgetCatRepo);
                verify(simulationRepo, never()).save(any());

                ApplyRequest request = new ApplyRequest("2026-11", viewed.feedbackId(), List.of(7, 5));
                ApplyResponse result = service.apply(1L, request);

                assertThat(result.applied()).isTrue();
                assertThat(result.updatedCategories()).hasSize(2);
                assertThat(result.updatedCategories().stream()
                                .mapToLong(change -> change.before() - change.after())
                                .sum())
                        .isEqualTo(simulated.reductionTotal());

                // cat7: 200,000 - 40,000 = 160,000
                ApplyResponse.CategoryChange change7 = result.updatedCategories().stream()
                        .filter(c -> c.categoryId() == 7)
                        .findFirst()
                        .orElseThrow();
                assertThat(change7.before()).isEqualTo(200_000);
                assertThat(change7.after()).isEqualTo(160_000);

                // cat5: 100,000 - 20,000 = 80,000
                ApplyResponse.CategoryChange change5 = result.updatedCategories().stream()
                        .filter(c -> c.categoryId() == 5)
                        .findFirst()
                        .orElseThrow();
                assertThat(change5.before()).isEqualTo(100_000);
                assertThat(change5.after()).isEqualTo(80_000);

                // 총 예산: 1,600,000 - 60,000 = 1,540,000
                assertThat(result.newTotalBudget()).isEqualTo(1_540_000);

                // BudgetCategory.modifyAmount() 호출 확인
                assertThat(cat7.getAmount()).isEqualTo(160_000);
                assertThat(cat5.getAmount()).isEqualTo(80_000);
                assertThat(budget.getTotalBudget()).isEqualTo(1_540_000);

                // 시뮬레이션 이력 저장 확인
                verify(simulationRepo)
                        .save(argThat(simulation -> simulation.getFeedbackId().equals(viewed.feedbackId())));
                verify(feedbackRepo, times(2)).findById(viewed.feedbackId());
            }

            @Test
            @DisplayName("감축 후 금액이 10,000원 미만이면 하한 10,000원 적용")
            void minimumBudgetFloor() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                when(simulationRepo.findByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 1L))
                        .thenReturn(Optional.empty());

                Budget budget = Budget.builder()
                        .id(10L)
                        .userId(1L)
                        .yearMonth(LocalDate.of(2026, 11, 1))
                        .totalBudget(1_600_000)
                        .build();
                when(budgetRepo.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 11, 1)))
                        .thenReturn(Optional.of(budget));

                // 예산이 reductionWon(40,000)보다 적은 경우
                BudgetCategory cat7 = BudgetCategory.builder()
                        .budgetId(10L)
                        .categoryId(7)
                        .amount(30_000)
                        .build();
                when(budgetCatRepo.findByBudgetId(10L)).thenReturn(List.of(cat7));

                when(simulationRepo.save(any(SavingSimulation.class))).thenAnswer(inv -> inv.getArgument(0));

                ApplyRequest request = new ApplyRequest("2026-11", 1L, List.of(7));
                ApplyResponse result = service.apply(1L, request);

                // 30,000 - 40,000 = -10,000 → 하한 10,000 적용
                assertThat(cat7.getAmount()).isEqualTo(10_000);
                ApplyResponse.CategoryChange change = result.updatedCategories().get(0);
                assertThat(change.before()).isEqualTo(30_000);
                assertThat(change.after()).isEqualTo(10_000);
            }

            @Test
            @DisplayName("예산 총액이 이미 가용예산을 넘은 상태여도 감축은 성공한다 (#129)")
            void appliesEvenWhenBudgetAlreadyAboveAvailable() throws Exception {
                // 소득 3,000,000 = 가용예산 3,000,000인데 예산 총액은 4,000,000
                UserAccount user = userWithGoal(3_000_000, 0, 0);
                // 예상 변동지출 2,000,000원 — 예산 총액(4,000,000원)과 다르다
                when(expenseRepo.sumVariableExpensesByPeriod(eq(1L), any(), any()))
                        .thenReturn(2_000_000L);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));
                when(simulationRepo.findByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 1L))
                        .thenReturn(Optional.empty());

                Budget budget = Budget.builder()
                        .id(10L)
                        .userId(1L)
                        .yearMonth(LocalDate.of(2026, 11, 1))
                        .totalBudget(4_000_000)
                        .build();
                when(budgetRepo.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 11, 1)))
                        .thenReturn(Optional.of(budget));

                BudgetCategory cat7 = BudgetCategory.builder()
                        .budgetId(10L)
                        .categoryId(7)
                        .amount(200_000)
                        .build();
                when(budgetCatRepo.findByBudgetId(10L)).thenReturn(List.of(cat7));
                when(simulationRepo.save(any(SavingSimulation.class))).thenAnswer(inv -> inv.getArgument(0));

                ApplyResponse result = service.apply(1L, new ApplyRequest("2026-11", 1L, List.of(7)));

                assertThat(result.applied()).isTrue();
                assertThat(result.newTotalBudget()).isEqualTo(3_960_000);
                // simulate와 같은 기준: (3,000,000 − 2,000,000) + 감축 40,000
                assertThat(result.monthlySavings()).isEqualTo(1_040_000);
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("예산이 없으면 BUDGET_NOT_FOUND 예외")
            void budgetNotFound() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                when(simulationRepo.findByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 1L))
                        .thenReturn(Optional.empty());

                when(budgetRepo.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 11, 1)))
                        .thenReturn(Optional.empty());

                ApplyRequest request = new ApplyRequest("2026-11", 1L, List.of(7));

                assertThatThrownBy(() -> service.apply(1L, request))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.BUDGET_NOT_FOUND));
            }

            @Test
            @DisplayName("카테고리 예산이 없으면 BUDGET_CATEGORY_NOT_FOUND 예외")
            void categoryNotFound() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                when(simulationRepo.findByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 1L))
                        .thenReturn(Optional.empty());

                Budget budget = Budget.builder()
                        .id(10L)
                        .userId(1L)
                        .yearMonth(LocalDate.of(2026, 11, 1))
                        .totalBudget(1_600_000)
                        .build();
                when(budgetRepo.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 11, 1)))
                        .thenReturn(Optional.of(budget));

                // 빈 카테고리 목록
                when(budgetCatRepo.findByBudgetId(10L)).thenReturn(List.of());

                ApplyRequest request = new ApplyRequest("2026-11", 1L, List.of(7));

                assertThatThrownBy(() -> service.apply(1L, request))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.BUDGET_CATEGORY_NOT_FOUND));
            }

            @Test
            @DisplayName("선택한 카테고리가 enrichedActions에 없으면 INVALID_CATEGORY_SELECTION 예외")
            void invalidCategorySelection() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                when(simulationRepo.findByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 1L))
                        .thenReturn(Optional.empty());

                Budget budget = Budget.builder()
                        .id(10L)
                        .userId(1L)
                        .yearMonth(LocalDate.of(2026, 11, 1))
                        .totalBudget(1_600_000)
                        .build();
                when(budgetRepo.findByUserIdAndYearMonth(1L, LocalDate.of(2026, 11, 1)))
                        .thenReturn(Optional.of(budget));

                when(budgetCatRepo.findByBudgetId(10L)).thenReturn(List.of());

                ApplyRequest request = new ApplyRequest("2026-11", 1L, List.of(99));

                assertThatThrownBy(() -> service.apply(1L, request))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.INVALID_CATEGORY_SELECTION));
            }

            @Test
            @DisplayName("지정한 피드백이 없으면 시뮬레이션과 예산 반영을 거절한다")
            void requestedFeedbackNotFound() {
                when(userRepo.findById(1L)).thenReturn(Optional.of(userWithGoal(3_000_000, 0, 500_000)));
                when(feedbackRepo.findById(99L)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.simulate(1L, new SimulateRequest("2026-11", 99L, List.of(7))))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.FEEDBACK_NOT_FOUND));
                assertThatThrownBy(() -> service.apply(1L, new ApplyRequest("2026-11", 99L, List.of(7))))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.FEEDBACK_NOT_FOUND));
                verifyNoInteractions(budgetRepo, budgetCatRepo, simulationRepo);
            }

            @ParameterizedTest
            @CsvSource({"2, 2026-11-01, WEEKLY", "1, 2026-10-01, WEEKLY", "1, 2026-11-01, MONTHLY"})
            @DisplayName("다른 사용자·월·유형의 피드백은 시뮬레이션과 예산 반영을 거절한다")
            void rejectsMismatchedFeedback(long ownerId, LocalDate yearMonth, String periodType) throws Exception {
                when(userRepo.findById(1L)).thenReturn(Optional.of(userWithGoal(3_000_000, 0, 500_000)));
                AiFeedback feedback = AiFeedback.builder()
                        .id(1L)
                        .userId(ownerId)
                        .yearMonth(yearMonth)
                        .periodType(periodType)
                        .payload(buildPayloadWithEnrichedActions())
                        .build();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedback));

                assertThatThrownBy(() -> service.simulate(1L, new SimulateRequest("2026-11", 1L, List.of(7))))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.FEEDBACK_NOT_FOUND));
                assertThatThrownBy(() -> service.apply(1L, new ApplyRequest("2026-11", 1L, List.of(7))))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.FEEDBACK_NOT_FOUND));
                verifyNoInteractions(budgetRepo, budgetCatRepo, simulationRepo);
            }

            @Test
            @DisplayName("사용자가 확인한 감축액과 현재 계산이 다르면 예산을 바꾸지 않고 재확인을 요구한다 (#127)")
            void confirmedAmountMismatch_rejected() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));
                when(feedbackRepo.findById(1L))
                        .thenReturn(Optional.of(feedbackWith(buildPayloadWithEnrichedActions())));

                // 식대(7) 감축 제안은 40,000원인데 사용자는 50,000원으로 알고 있다
                ApplyRequest request = new ApplyRequest("2026-11", 1L, List.of(7), 50_000L);

                assertThatThrownBy(() -> service.apply(1L, request))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.SIMULATION_REDUCTION_CHANGED));
                verifyNoInteractions(budgetRepo, budgetCatRepo);
                verify(simulationRepo, never()).save(any());
            }

            @Test
            @DisplayName("일부 카테고리만 반영했어도 같은 피드백의 나머지 적용을 거절한다")
            void alreadyApplied() throws Exception {
                UserAccount user = userWithGoal(3_000_000, 0, 500_000);
                when(userRepo.findById(1L)).thenReturn(Optional.of(user));

                String payload = buildPayloadWithEnrichedActions();
                when(feedbackRepo.findById(1L)).thenReturn(Optional.of(feedbackWith(payload)));

                SavingSimulation existing = SavingSimulation.builder()
                        .id(100L)
                        .userId(1L)
                        .feedbackId(1L)
                        .isApplied(true)
                        .build();
                existing.addItem(com.planus.backend.domain.savingssimulation.entity.SavingSimulationItem.builder()
                        .categoryId(7L)
                        .savingAmount(40000L)
                        .annualSaving(480000L)
                        .build());
                when(simulationRepo.findByUserIdAndFeedbackIdAndIsAppliedTrue(1L, 1L))
                        .thenReturn(Optional.of(existing));

                ApplyRequest request = new ApplyRequest("2026-11", 1L, List.of(5));

                assertThatThrownBy(() -> service.apply(1L, request))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                                .isEqualTo(GeneralErrorCode.ALREADY_APPLIED));
                verifyNoInteractions(budgetRepo, budgetCatRepo);
                verify(simulationRepo, never()).save(any());
            }
        }
    }
}
