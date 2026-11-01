package com.planus.backend.domain.aibudget.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aibudget.client.AiBudgetClient;
import com.planus.backend.domain.aibudget.client.dto.AiBudgetResponse;
import com.planus.backend.domain.aibudget.dto.BudgetCategoryUpdateRequest;
import com.planus.backend.domain.aibudget.dto.BudgetSummaryResponse;
import com.planus.backend.domain.aibudget.dto.PeerComparisonResponse;
import com.planus.backend.domain.aibudget.entity.BudgetPlanAnalysis;
import com.planus.backend.domain.aibudget.entity.BudgetPlanRequest;
import com.planus.backend.domain.aibudget.entity.BudgetPlanStatus;
import com.planus.backend.domain.aibudget.repository.BudgetPlanAnalysisRepository;
import com.planus.backend.domain.aibudget.repository.BudgetPlanRequestRepository;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class BudgetPlanServiceTest {

    private UserAccountRepository userAccountRepository;
    private BudgetPlanRequestRepository budgetPlanRequestRepository;
    private BudgetPlanAnalysisRepository budgetPlanAnalysisRepository;
    private ExpenseRepository expenseRepository;
    private BudgetRepository budgetRepository;
    private BudgetCategoryRepository budgetCategoryRepository;
    private AiBudgetClient aiBudgetClient;
    private FcmService fcmService;
    private ObjectMapper objectMapper;
    private ApplicationContext applicationContext;

    private BudgetPlanService service;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        budgetPlanRequestRepository = mock(BudgetPlanRequestRepository.class);
        budgetPlanAnalysisRepository = mock(BudgetPlanAnalysisRepository.class);
        expenseRepository = mock(ExpenseRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        budgetCategoryRepository = mock(BudgetCategoryRepository.class);
        aiBudgetClient = mock(AiBudgetClient.class);
        fcmService = mock(FcmService.class);
        objectMapper = new ObjectMapper();
        applicationContext = mock(ApplicationContext.class);

        service = new BudgetPlanService(
                userAccountRepository,
                budgetPlanRequestRepository,
                budgetPlanAnalysisRepository,
                expenseRepository,
                budgetRepository,
                budgetCategoryRepository,
                aiBudgetClient,
                fcmService,
                objectMapper,
                applicationContext);
    }

    @Nested
    @DisplayName("triggerBudgetPlan()")
    class TriggerBudgetPlan {

        @Test
        @DisplayName("PENDING 상태의 기존 요청이 있으면 예외 발생")
        void existingPending_throws() {
            Long userId = 1L;
            LocalDate yearMonth = LocalDate.now().withDayOfMonth(1);

            BudgetPlanRequest existing = BudgetPlanRequest.builder()
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .build();
            // 기본 상태가 PENDING

            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.triggerBudgetPlan(userId)).isInstanceOf(GeneralException.class);
        }

        @Test
        @DisplayName("IN_PROGRESS 상태의 기존 요청이 있으면 예외 발생")
        void existingInProgress_throws() {
            Long userId = 1L;
            LocalDate yearMonth = LocalDate.now().withDayOfMonth(1);

            BudgetPlanRequest existing = BudgetPlanRequest.builder()
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .build();
            existing.markInProgress();

            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.triggerBudgetPlan(userId)).isInstanceOf(GeneralException.class);
        }
    }

    @Nested
    @DisplayName("markInProgress()")
    class MarkInProgress {

        @Test
        @DisplayName("정상적으로 상태를 IN_PROGRESS로 변경")
        void success() {
            Long userId = 1L;
            LocalDate yearMonth = LocalDate.of(2026, 10, 1);

            BudgetPlanRequest request = BudgetPlanRequest.builder()
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .build();

            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.of(request));

            service.markInProgress(userId, yearMonth);

            assertThat(request.getStatus()).isEqualTo(BudgetPlanStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("요청이 없으면 예외 발생")
        void notFound_throws() {
            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(anyLong(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.markInProgress(1L, LocalDate.of(2026, 10, 1)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("markFailed()")
    class MarkFailed {

        @Test
        @DisplayName("정상적으로 상태를 FAILED로 변경하고 에러 메시지 저장")
        void success() {
            Long userId = 1L;
            LocalDate yearMonth = LocalDate.of(2026, 10, 1);

            BudgetPlanRequest request = BudgetPlanRequest.builder()
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .build();

            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.of(request));

            service.markFailed(userId, yearMonth, "timeout");

            assertThat(request.getStatus()).isEqualTo(BudgetPlanStatus.FAILED);
            assertThat(request.getErrorMessage()).isEqualTo("timeout");
        }

        @Test
        @DisplayName("요청이 없어도 예외 발생하지 않음")
        void notFound_noException() {
            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(anyLong(), any()))
                    .thenReturn(Optional.empty());

            assertThatCode(() -> service.markFailed(1L, LocalDate.of(2026, 10, 1), "error"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("getBudgetSummary()")
    class GetBudgetSummary {

        private final Long userId = 1L;
        private final LocalDate yearMonth = LocalDate.of(2026, 10, 1);

        @Test
        @DisplayName("예산이 없으면 BUDGET_NOT_FOUND 예외")
        void notFound_throws() {
            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getBudgetSummary(userId, yearMonth))
                    .isInstanceOf(GeneralException.class);
        }

        @Test
        @DisplayName("정상적으로 요약 응답 반환")
        void success() {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(600000)
                    .aiTotalBudget(600000L)
                    .aiSummary("AI 분석 요약")
                    .build();

            BudgetCategory cat1 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(100000)
                    .aiAmount(100000L)
                    .userModified(false)
                    .build();
            BudgetCategory cat2 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(4)
                    .amount(500000)
                    .aiAmount(500000L)
                    .userModified(false)
                    .build();

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of(cat1, cat2));

            BudgetSummaryResponse result = service.getBudgetSummary(userId, yearMonth);

            assertThat(result.aiAnalysis()).isEqualTo("AI 분석 요약");
            assertThat(result.aiTotalBudget()).isEqualTo(600000);
            assertThat(result.userTotalBudget()).isEqualTo(600000);
            assertThat(result.budgetDifference()).isEqualTo(0);
            assertThat(result.categories()).hasSize(2);
        }

        @Test
        @DisplayName("aiTotalBudget이 null이면 BudgetPlanRequest에서 백필")
        void aiTotalBudget_null_backfill() throws Exception {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(500000)
                    .build();

            // AI 응답 JSON 준비 (strategy.notes + budget.allocations)
            AiBudgetResponse aiResponse = new AiBudgetResponse(
                    null,
                    null,
                    null,
                    new AiBudgetResponse.Strategy(null, null, null, "AI가 분석한 내용입니다."),
                    new AiBudgetResponse.Budget(
                            List.of(new AiBudgetResponse.Budget.Allocation("식료품", 300000, 0, "reason")), 200000),
                    null);
            String aiResponseJson = new ObjectMapper().writeValueAsString(aiResponse);

            BudgetPlanRequest planRequest = BudgetPlanRequest.builder()
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .build();
            planRequest.markInProgress();
            planRequest.markCompleted(aiResponseJson);

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of());
            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.of(planRequest));
            when(budgetPlanAnalysisRepository.findByBudgetId(10L)).thenReturn(Optional.empty());

            BudgetSummaryResponse result = service.getBudgetSummary(userId, yearMonth);

            // 백필 확인: AI 응답에서 strategy.notes와 aiTotalBudget이 채워짐
            assertThat(result.aiAnalysis()).isEqualTo("AI가 분석한 내용입니다.");
            assertThat(result.aiTotalBudget()).isEqualTo(500000); // 300000 + 200000
            assertThat(budget.getAiSummary()).isEqualTo("AI가 분석한 내용입니다.");
            assertThat(budget.getAiTotalBudget()).isEqualTo(500000L);
        }

        @Test
        @DisplayName("카테고리 aiAmount가 null이면 현재 amount로 백필")
        void categoryAiAmount_null_backfill() {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(600000)
                    .aiTotalBudget(600000L)
                    .aiSummary("분석 요약")
                    .build();

            // aiAmount가 null인 기존 데이터
            BudgetCategory cat = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(100000)
                    .build();

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of(cat));

            BudgetSummaryResponse result = service.getBudgetSummary(userId, yearMonth);

            // aiAmount가 amount 값으로 백필됨
            assertThat(cat.getAiAmount()).isEqualTo(100000L);
            assertThat(result.categories().get(0).aiAmount()).isEqualTo(100000);
            assertThat(result.categories().get(0).difference()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("saveCategories()")
    class SaveCategories {

        private final Long userId = 1L;
        private final LocalDate yearMonth = LocalDate.of(2026, 10, 1);

        @Test
        @DisplayName("예산이 없으면 BUDGET_NOT_FOUND 예외")
        void budgetNotFound_throws() {
            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.empty());

            List<BudgetCategoryUpdateRequest.CategoryAmount> items =
                    List.of(new BudgetCategoryUpdateRequest.CategoryAmount(1, 50000L));

            assertThatThrownBy(() -> service.saveCategories(userId, yearMonth, items))
                    .isInstanceOf(GeneralException.class);
        }

        @Test
        @DisplayName("존재하지 않는 카테고리 ID가 포함되면 BUDGET_CATEGORY_NOT_FOUND 예외")
        void categoryNotFound_throws() {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(600000)
                    .build();

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of());

            List<BudgetCategoryUpdateRequest.CategoryAmount> items =
                    List.of(new BudgetCategoryUpdateRequest.CategoryAmount(99, 50000L));

            assertThatThrownBy(() -> service.saveCategories(userId, yearMonth, items))
                    .isInstanceOf(GeneralException.class);
        }

        @Test
        @DisplayName("여러 카테고리 일괄 수정 시 금액 변경, userModified 설정, 총액 재계산")
        void success() {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(600000)
                    .aiTotalBudget(600000L)
                    .aiSummary("분석")
                    .build();

            BudgetCategory cat1 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(100000)
                    .aiAmount(100000L)
                    .userModified(false)
                    .build();
            BudgetCategory cat2 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(4)
                    .amount(500000)
                    .aiAmount(500000L)
                    .userModified(false)
                    .build();

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of(cat1, cat2));

            List<BudgetCategoryUpdateRequest.CategoryAmount> items = List.of(
                    new BudgetCategoryUpdateRequest.CategoryAmount(1, 80000L),
                    new BudgetCategoryUpdateRequest.CategoryAmount(4, 400000L));

            BudgetSummaryResponse result = service.saveCategories(userId, yearMonth, items);

            assertThat(cat1.getAmount()).isEqualTo(80000);
            assertThat(cat1.getUserModified()).isTrue();
            assertThat(cat2.getAmount()).isEqualTo(400000);
            assertThat(cat2.getUserModified()).isTrue();
            // delta = (80000-100000) + (400000-500000) = -120000, 총액 = 600000 + (-120000) = 480000
            assertThat(budget.getTotalBudget()).isEqualTo(480000);
            assertThat(result.userTotalBudget()).isEqualTo(480000);
            assertThat(result.budgetDifference()).isEqualTo(-120000);
        }

        @Test
        @DisplayName("금액이 변경되지 않은 카테고리는 userModified가 설정되지 않는다")
        void unchanged_category_not_marked() {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(600000)
                    .aiTotalBudget(600000L)
                    .aiSummary("분석")
                    .build();

            BudgetCategory cat1 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(100000)
                    .aiAmount(100000L)
                    .userModified(false)
                    .build();

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of(cat1));

            // 같은 금액으로 저장
            List<BudgetCategoryUpdateRequest.CategoryAmount> items =
                    List.of(new BudgetCategoryUpdateRequest.CategoryAmount(1, 100000L));

            service.saveCategories(userId, yearMonth, items);

            assertThat(cat1.getUserModified()).isFalse();
        }

        @Test
        @DisplayName("savings 포함 총액에서 카테고리 수정 시 delta만 반영된다")
        void savings_preserved_after_modify() {
            // totalBudget = allocations(300000) + savings(200000) = 500000
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(500000)
                    .aiTotalBudget(500000L)
                    .aiSummary("분석")
                    .build();

            BudgetCategory cat1 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(200000)
                    .aiAmount(200000L)
                    .userModified(false)
                    .build();
            BudgetCategory cat2 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(4)
                    .amount(100000)
                    .aiAmount(100000L)
                    .userModified(false)
                    .build();

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of(cat1, cat2));

            // cat1만 50000 감소
            List<BudgetCategoryUpdateRequest.CategoryAmount> items = List.of(
                    new BudgetCategoryUpdateRequest.CategoryAmount(1, 150000L),
                    new BudgetCategoryUpdateRequest.CategoryAmount(4, 100000L));

            service.saveCategories(userId, yearMonth, items);

            // savings(200000)가 보존되어야 함: 500000 + (-50000) = 450000
            assertThat(budget.getTotalBudget()).isEqualTo(450000);
        }

        @Test
        @DisplayName("레거시 데이터 첫 수정 시 aiAmount가 수정 전 값으로 백필된다")
        void legacy_aiAmount_backfill_before_modify() {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(100000)
                    .aiTotalBudget(100000L)
                    .aiSummary("분석")
                    .build();

            // aiAmount가 null인 레거시 데이터
            BudgetCategory cat1 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(100000)
                    .userModified(false)
                    .build();

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of(cat1));

            List<BudgetCategoryUpdateRequest.CategoryAmount> items =
                    List.of(new BudgetCategoryUpdateRequest.CategoryAmount(1, 50000L));

            service.saveCategories(userId, yearMonth, items);

            // aiAmount는 수정 전 원본(100000)으로 백필, amount는 수정값
            assertThat(cat1.getAiAmount()).isEqualTo(100000L);
            assertThat(cat1.getAmount()).isEqualTo(50000);
            assertThat(cat1.getUserModified()).isTrue();
        }

        @Test
        @DisplayName("수정 후 aiAmount는 원래 AI 제안값을 유지한다")
        void aiAmount_preserved_after_modify() {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(100000)
                    .aiTotalBudget(100000L)
                    .aiSummary("분석")
                    .build();

            BudgetCategory cat1 = BudgetCategory.builder()
                    .budgetId(10L)
                    .categoryId(1)
                    .amount(100000)
                    .aiAmount(100000L)
                    .userModified(false)
                    .build();

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetCategoryRepository.findByBudgetId(10L)).thenReturn(List.of(cat1));

            List<BudgetCategoryUpdateRequest.CategoryAmount> items =
                    List.of(new BudgetCategoryUpdateRequest.CategoryAmount(1, 50000L));

            service.saveCategories(userId, yearMonth, items);

            assertThat(cat1.getAmount()).isEqualTo(50000);
            assertThat(cat1.getAiAmount()).isEqualTo(100000L);

            BudgetSummaryResponse result = service.getBudgetSummary(userId, yearMonth);

            BudgetSummaryResponse.CategoryBudget catResult = result.categories().get(0);
            assertThat(catResult.aiAmount()).isEqualTo(100000);
            assertThat(catResult.userAmount()).isEqualTo(50000);
            assertThat(catResult.difference()).isEqualTo(-50000);
            assertThat(catResult.userModified()).isTrue();
        }
    }

    @Nested
    @DisplayName("getPeerComparison()")
    class GetPeerComparison {

        private final Long userId = 1L;
        private final LocalDate yearMonth = LocalDate.of(2026, 10, 1);

        @Test
        @DisplayName("정상 조회: 9개 카테고리 반환, difference 계산 검증")
        void success() {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(600000)
                    .aiTotalBudget(600000L)
                    .build();

            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("또래인원", 141);

            Map<String, Object> peerMap = new LinkedHashMap<>();
            peerMap.put("식료품", 120000L);
            peerMap.put("의류신발", 50000L);
            peerMap.put("보건", 30000L);
            peerMap.put("교통", 80000L);
            peerMap.put("오락문화", 60000L);
            peerMap.put("교육", 40000L);
            peerMap.put("식대", 377268L);
            peerMap.put("숙박여행", 20000L);
            peerMap.put("정보통신", 35000L);
            observation.put("또래", peerMap);

            BudgetPlanAnalysis analysis = BudgetPlanAnalysis.builder()
                    .budgetId(10L)
                    .observation(observation)
                    .build();

            // 실시간 소비 데이터 mock (categoryId, sum 튜플)
            LocalDateTime from = yearMonth.atStartOfDay();
            LocalDateTime to = yearMonth.plusMonths(1).atStartOfDay().minusSeconds(1);
            List<Object[]> expenseRows = List.of(
                    new Object[] {1, 100000L},
                    new Object[] {2, 30000L},
                    new Object[] {3, 10000L},
                    new Object[] {4, 50000L},
                    new Object[] {5, 40000L},
                    new Object[] {6, 20000L},
                    new Object[] {7, 154900L},
                    new Object[] {8, 0L},
                    new Object[] {9, 25000L});

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetPlanAnalysisRepository.findByBudgetId(10L)).thenReturn(Optional.of(analysis));
            when(expenseRepository.sumExpensesGroupByCategory(userId, from, to)).thenReturn(expenseRows);

            PeerComparisonResponse result = service.getPeerComparison(userId, yearMonth);

            assertThat(result.peerGroupSize()).isEqualTo(141);
            // totalSpending = 실시간 소비 합계
            long expectedTotal = 100000L + 30000L + 10000L + 50000L + 40000L + 20000L + 154900L + 0L + 25000L;
            assertThat(result.totalSpending()).isEqualTo(expectedTotal);
            assertThat(result.categories()).hasSize(9);

            // 식대 카테고리 검증
            PeerComparisonResponse.CategoryComparison meal = result.categories().stream()
                    .filter(c -> c.categoryId() == 7)
                    .findFirst()
                    .orElseThrow();
            assertThat(meal.categoryName()).isEqualTo("식대");
            assertThat(meal.myAmount()).isEqualTo(154900L);
            assertThat(meal.peerAmount()).isEqualTo(377268L);
            assertThat(meal.difference()).isEqualTo(154900L - 377268L);

            // 식료품 카테고리 검증
            PeerComparisonResponse.CategoryComparison grocery = result.categories().stream()
                    .filter(c -> c.categoryId() == 1)
                    .findFirst()
                    .orElseThrow();
            assertThat(grocery.myAmount()).isEqualTo(100000L);
            assertThat(grocery.peerAmount()).isEqualTo(120000L);
            assertThat(grocery.difference()).isEqualTo(-20000L);
        }

        @Test
        @DisplayName("예산 미존재 시 BUDGET_NOT_FOUND 예외")
        void budgetNotFound_throws() {
            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPeerComparison(userId, yearMonth))
                    .isInstanceOf(GeneralException.class);
        }

        @Test
        @DisplayName("analysis 미존재 시 BUDGET_PLAN_NOT_FOUND 예외")
        void analysisNotFound_throws() {
            Budget budget = Budget.builder()
                    .id(10L)
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .totalBudget(600000)
                    .aiTotalBudget(600000L)
                    .build();

            when(budgetRepository.findByUserIdAndYearMonth(userId, yearMonth)).thenReturn(Optional.of(budget));
            when(budgetPlanAnalysisRepository.findByBudgetId(10L)).thenReturn(Optional.empty());
            // 백필 시도도 실패 (planRequest 없음)
            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPeerComparison(userId, yearMonth))
                    .isInstanceOf(GeneralException.class);
        }
    }
}
