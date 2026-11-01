package com.planus.backend.domain.aibudget.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aibudget.client.AiBudgetClient;
import com.planus.backend.domain.aibudget.client.dto.AiBudgetRequest;
import com.planus.backend.domain.aibudget.client.dto.AiBudgetResponse;
import com.planus.backend.domain.aibudget.dto.BudgetCategoryUpdateRequest;
import com.planus.backend.domain.aibudget.dto.BudgetSummaryResponse;
import com.planus.backend.domain.aibudget.entity.BudgetPlanAnalysis;
import com.planus.backend.domain.aibudget.entity.BudgetPlanRequest;
import com.planus.backend.domain.aibudget.entity.BudgetPlanStatus;
import com.planus.backend.domain.aibudget.repository.BudgetPlanAnalysisRepository;
import com.planus.backend.domain.aibudget.repository.BudgetPlanRequestRepository;
import com.planus.backend.domain.aifeedback.core.Categories;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** AI 예산 산출 오케스트레이터. 비동기로 AI 서버를 호출하고 결과를 저장한다. */
@Service
@RequiredArgsConstructor
public class BudgetPlanService {

    private static final Logger log = LoggerFactory.getLogger(BudgetPlanService.class);

    private final UserAccountRepository userAccountRepository;
    private final BudgetPlanRequestRepository budgetPlanRequestRepository;
    private final BudgetPlanAnalysisRepository budgetPlanAnalysisRepository;
    private final ExpenseRepository expenseRepository;
    private final BudgetRepository budgetRepository;
    private final BudgetCategoryRepository budgetCategoryRepository;
    private final AiBudgetClient aiBudgetClient;
    private final FcmService fcmService;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    /**
     * 예산 산출을 트리거한다. PENDING 상태의 BudgetPlanRequest를 생성하고 비동기 처리를 시작한다.
     *
     * @param userId 사용자 ID
     * @throws GeneralException 이미 진행 중인 요청이 있는 경우
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void triggerBudgetPlan(Long userId) {
        // 프로필 완료 여부 검증
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));
        if (!user.isProfileCompleted()) {
            throw new GeneralException(GeneralErrorCode.PROFILE_NOT_COMPLETED);
        }

        LocalDate yearMonth = LocalDate.now().withDayOfMonth(1);

        Optional<BudgetPlanRequest> existing = budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth);
        if (existing.isPresent()) {
            BudgetPlanRequest req = existing.get();
            if (req.getStatus() == BudgetPlanStatus.PENDING || req.getStatus() == BudgetPlanStatus.IN_PROGRESS) {
                // 15분 이상 지난 PENDING/IN_PROGRESS는 stuck으로 간주하여 재시도 허용
                if (req.getCreatedAt().plusMinutes(15).isBefore(LocalDateTime.now())) {
                    log.warn("stuck 상태의 요청 삭제 userId={} status={}", userId, req.getStatus());
                } else {
                    throw new GeneralException(GeneralErrorCode.BUDGET_PLAN_IN_PROGRESS);
                }
            }
            budgetPlanRequestRepository.delete(req);
            budgetPlanRequestRepository.flush();
        }

        BudgetPlanRequest request =
                BudgetPlanRequest.builder().userId(userId).yearMonth(yearMonth).build();
        budgetPlanRequestRepository.save(request);

        // 트랜잭션 커밋 후 비동기 실행 (커밋 전에 시작하면 레코드를 찾지 못함)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                BudgetPlanService self = applicationContext.getBean(BudgetPlanService.class);
                try {
                    self.executePlanAsync(userId, yearMonth);
                } catch (org.springframework.core.task.TaskRejectedException e) {
                    log.error("AI 예산 산출 작업 제출 거부 userId={}", userId, e);
                    self.markFailed(userId, yearMonth, "처리 대기열 초과");
                }
            }
        });
    }

    /** 비동기로 AI 예산 산출을 실행한다. */
    @Async("aiBudgetExecutor")
    public void executePlanAsync(Long userId, LocalDate yearMonth) {
        BudgetPlanService self = applicationContext.getBean(BudgetPlanService.class);
        try {
            // 1. 상태를 IN_PROGRESS로 즉시 커밋
            self.markInProgress(userId, yearMonth);

            // 2. 사용자 프로필 로드 + AI 요청 빌드
            AiBudgetRequest aiRequest = self.buildRequestInTx(userId, yearMonth);

            // 3. AI 서버 호출 (트랜잭션 밖 — 수 분 소요 가능)
            log.info("AI 예산 산출 요청 시작 userId={}", userId);
            AiBudgetResponse aiResponse = aiBudgetClient.requestPlan(aiRequest);

            // 4. 결과 저장
            Long budgetId = self.saveResult(userId, yearMonth, aiResponse);

            // 5. 분석 데이터 저장 (별도 트랜잭션 — 실패해도 예산 결과에 영향 없음)
            self.saveAnalysisSafe(budgetId, aiResponse);

            // 6. FCM 푸시 알림
            fcmService.sendBudgetPlanComplete(userId);

            log.info("AI 예산 산출 완료 userId={} yearMonth={}", userId, yearMonth);
        } catch (Exception e) {
            log.error("AI 예산 산출 실패 userId={} yearMonth={}", userId, yearMonth, e);
            self.markFailed(userId, yearMonth, e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInProgress(Long userId, LocalDate yearMonth) {
        BudgetPlanRequest planRequest = budgetPlanRequestRepository
                .findByUserIdAndYearMonth(userId, yearMonth)
                .orElseThrow(() -> new IllegalStateException("BudgetPlanRequest not found"));
        planRequest.markInProgress();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiBudgetRequest buildRequestInTx(Long userId, LocalDate yearMonth) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));
        return buildRequest(user, yearMonth);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long saveResult(Long userId, LocalDate yearMonth, AiBudgetResponse aiResponse) {
        BudgetPlanRequest planRequest = budgetPlanRequestRepository
                .findByUserIdAndYearMonth(userId, yearMonth)
                .orElseThrow(() -> new IllegalStateException("BudgetPlanRequest not found"));

        Budget budget = upsertBudget(userId, yearMonth, aiResponse);

        try {
            String responseJson = objectMapper.writeValueAsString(aiResponse);
            planRequest.markCompleted(responseJson);
        } catch (Exception e) {
            planRequest.markCompleted("{}");
            log.warn("AI 응답 JSON 직렬화 실패", e);
        }

        return budget.getId();
    }

    /** AI 분석 데이터를 별도 트랜잭션으로 저장한다. 실패해도 예산 저장에 영향을 주지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAnalysisSafe(Long budgetId, AiBudgetResponse response) {
        try {
            saveAnalysis(budgetId, response);
        } catch (Exception e) {
            log.warn("AI 분석 데이터 저장 실패 budgetId={}", budgetId, e);
        }
    }

    private AiBudgetRequest buildRequest(UserAccount user, LocalDate yearMonth) {
        // 프로필 매핑
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("성별", genderCode(user.getGender()));
        profile.put("나이", user.getAge());
        profile.put("취업여부", boolToCode(user.getEmploymentStatus()));
        profile.put("주택소유유무", boolToCode(user.getHomeOwnership()));
        profile.put("월소득", user.getMonthlyIncome());
        profile.put("월고정지출", user.totalFixedExpenses());
        profile.put("월목표저축액", user.getMonthlySavingsGoal());

        // 소비내역: 최근 3개월 카테고리별 월별 집계 (기타 카테고리 제외)
        List<Map<String, Object>> expenseHistory = buildExpenseHistory(user.getId(), yearMonth);

        return new AiBudgetRequest(String.valueOf(user.getId()), profile, user.getSelfIntroduction(), expenseHistory);
    }

    /** AI 서버 9개 카테고리 이름 (고정 순서). */
    private static final String[] AI_CATEGORIES = {"식료품", "의류신발", "보건", "교통", "오락문화", "교육", "식대", "숙박여행", "정보통신"};

    private List<Map<String, Object>> buildExpenseHistory(Long userId, LocalDate yearMonth) {
        LocalDateTime from = yearMonth.minusMonths(3).atStartOfDay();
        LocalDateTime to = yearMonth.atStartOfDay().minusSeconds(1);

        // 월별·카테고리별 집계: [year, month, categoryId, sum]
        List<Object[]> grouped = expenseRepository.sumExpensesGroupByMonthAndCategory(userId, from, to);

        // yearMonth 기준으로 묶기
        Map<String, Map<String, Long>> monthlyMap = new LinkedHashMap<>();
        for (Object[] row : grouped) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            int categoryId = ((Number) row[2]).intValue();
            long amount = ((Number) row[3]).longValue();

            if (categoryId == Categories.ETC_CATEGORY_ID) {
                continue;
            }

            String key = String.format("%04d-%02d", year, month);
            monthlyMap.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(Categories.name(categoryId), amount);
        }

        // 월별 Map을 리스트로 변환 (9개 카테고리 전부 포함, 없으면 0)
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> entry : monthlyMap.entrySet()) {
            Map<String, Object> monthEntry = new LinkedHashMap<>();
            monthEntry.put("기준월", entry.getKey());
            Map<String, Long> spending = entry.getValue();
            for (String cat : AI_CATEGORIES) {
                monthEntry.put(cat, spending.getOrDefault(cat, 0L));
            }
            history.add(monthEntry);
        }
        return history;
    }

    private Budget upsertBudget(Long userId, LocalDate yearMonth, AiBudgetResponse response) {
        if (response.budget() == null || response.budget().allocations() == null) {
            log.warn("AI 응답에 budget 정보 없음 userId={}", userId);
            return budgetRepository
                    .findByUserIdAndYearMonth(userId, yearMonth)
                    .orElseThrow(() -> new IllegalStateException("Budget not found"));
        }

        // totalBudget = allocations 합계 + savings
        long totalBudget = response.budget().allocations().stream()
                        .mapToLong(AiBudgetResponse.Budget.Allocation::amount)
                        .sum()
                + response.budget().savings();

        // AI 분석 요약 (strategy.notes)
        String aiSummary = response.strategy() != null ? response.strategy().notes() : null;

        Budget budget = budgetRepository
                .findByUserIdAndYearMonth(userId, yearMonth)
                .orElseGet(() -> budgetRepository.save(Budget.builder()
                        .userId(userId)
                        .yearMonth(yearMonth)
                        .totalBudget(totalBudget)
                        .build()));

        // totalBudget 갱신
        long diff = totalBudget - budget.getTotalBudget();
        if (diff != 0) {
            budget.addTotalBudget(diff);
        }

        // AI 관련 필드 저장
        budget.updateAiTotalBudget(totalBudget);
        budget.updateAiSummary(aiSummary);

        // 기존 카테고리별 배분 조회
        List<BudgetCategory> existingCategories = budgetCategoryRepository.findByBudgetId(budget.getId());
        Map<Integer, BudgetCategory> existingMap = new HashMap<>();
        for (BudgetCategory bc : existingCategories) {
            existingMap.put(bc.getCategoryId(), bc);
        }

        for (AiBudgetResponse.Budget.Allocation alloc : response.budget().allocations()) {
            int categoryId = Categories.id(alloc.category());
            if (categoryId == Categories.ETC_CATEGORY_ID) {
                log.warn("AI 응답에 미등록 카테고리명: '{}' userId={}", alloc.category(), userId);
                continue;
            }

            BudgetCategory existing = existingMap.get(categoryId);
            if (existing != null) {
                // AI 제안 금액은 항상 최신값으로 갱신
                existing.updateAiAmount(alloc.amount());
                long catDiff = alloc.amount() - existing.getAmount();
                if (catDiff != 0) {
                    existing.addAmount(catDiff);
                }
            } else {
                budgetCategoryRepository.save(BudgetCategory.builder()
                        .budgetId(budget.getId())
                        .categoryId(categoryId)
                        .amount(alloc.amount())
                        .aiAmount(alloc.amount())
                        .build());
            }
        }

        return budget;
    }

    /** AI 분석 데이터를 budget_plan_analysis 테이블에 upsert 한다. */
    private void saveAnalysis(Long budgetId, AiBudgetResponse response) {
        Map<String, Object> observation = toMap(response.observation());
        Map<String, Object> persona = toMap(response.persona());
        Map<String, Object> strategy = toMap(response.strategy());
        Map<String, Object> userContext = toMap(response.userContext());

        BudgetPlanAnalysis analysis =
                budgetPlanAnalysisRepository.findByBudgetId(budgetId).orElse(null);

        if (analysis != null) {
            analysis.updateAnalysis(observation, persona, strategy, userContext);
        } else {
            budgetPlanAnalysisRepository.save(BudgetPlanAnalysis.builder()
                    .budgetId(budgetId)
                    .observation(observation)
                    .persona(persona)
                    .strategy(strategy)
                    .userContext(userContext)
                    .build());
        }
    }

    /** DTO를 Map으로 변환한다. null이면 null 반환. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object dto) {
        if (dto == null) return null;
        return objectMapper.convertValue(dto, Map.class);
    }

    /** 예산 요약을 조회한다. 기존 데이터에 AI 필드가 없으면 BudgetPlanRequest에서 백필한다. */
    @Transactional
    public BudgetSummaryResponse getBudgetSummary(Long userId, LocalDate yearMonth) {
        Budget budget = budgetRepository
                .findByUserIdAndYearMonth(userId, yearMonth)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.BUDGET_NOT_FOUND));

        // AI 필드가 비어 있으면 기존 AI 응답에서 백필 (한 번만 시도)
        if (budget.getAiTotalBudget() == null) {
            backfillAiFields(userId, yearMonth, budget);
        }

        List<BudgetCategory> categories = budgetCategoryRepository.findByBudgetId(budget.getId());

        // 카테고리 aiAmount가 비어 있으면 현재 amount를 AI 원본으로 간주하여 백필
        for (BudgetCategory bc : categories) {
            if (bc.getAiAmount() == null) {
                bc.updateAiAmount(bc.getAmount());
            }
        }

        long aiTotal = budget.getAiTotalBudget() != null ? budget.getAiTotalBudget() : budget.getTotalBudget();
        long userTotal = budget.getTotalBudget();

        List<BudgetSummaryResponse.CategoryBudget> categoryBudgets = categories.stream()
                .map(bc -> {
                    long aiAmount = bc.getEffectiveAiAmount();
                    long userAmount = bc.getAmount();
                    BigDecimal percentage = userTotal > 0
                            ? BigDecimal.valueOf(userAmount)
                                    .multiply(BigDecimal.valueOf(100))
                                    .divide(BigDecimal.valueOf(userTotal), 1, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return new BudgetSummaryResponse.CategoryBudget(
                            bc.getCategoryId(),
                            Categories.name(bc.getCategoryId()),
                            aiAmount,
                            userAmount,
                            userAmount - aiAmount,
                            percentage,
                            Boolean.TRUE.equals(bc.getUserModified()));
                })
                .toList();

        return new BudgetSummaryResponse(
                budget.getAiSummary(), aiTotal, userTotal, userTotal - aiTotal, categoryBudgets);
    }

    /** 기존 데이터 하위 호환: BudgetPlanRequest의 AI 응답에서 aiSummary/aiTotalBudget을 백필한다. */
    private void backfillAiFields(Long userId, LocalDate yearMonth, Budget budget) {
        try {
            BudgetPlanRequest planRequest = budgetPlanRequestRepository
                    .findByUserIdAndYearMonth(userId, yearMonth)
                    .orElse(null);
            if (planRequest == null
                    || planRequest.getStatus() != BudgetPlanStatus.COMPLETED
                    || planRequest.getAiResponse() == null) {
                // 백필 불가 — totalBudget을 AI 총액 대용으로 설정하여 재시도 방지
                budget.updateAiTotalBudget(budget.getTotalBudget());
                return;
            }

            AiBudgetResponse response = objectMapper.readValue(planRequest.getAiResponse(), AiBudgetResponse.class);

            if (budget.getAiSummary() == null && response.strategy() != null) {
                budget.updateAiSummary(response.strategy().notes());
            }
            if (response.budget() != null && response.budget().allocations() != null) {
                long aiTotal = response.budget().allocations().stream()
                                .mapToLong(AiBudgetResponse.Budget.Allocation::amount)
                                .sum()
                        + response.budget().savings();
                budget.updateAiTotalBudget(aiTotal);
            } else {
                budget.updateAiTotalBudget(budget.getTotalBudget());
            }

            // 분석 데이터도 없으면 함께 백필 (실패해도 예산 백필에 영향 없도록 격리)
            try {
                if (budgetPlanAnalysisRepository.findByBudgetId(budget.getId()).isEmpty()) {
                    saveAnalysis(budget.getId(), response);
                }
            } catch (Exception ex) {
                log.warn("백필 중 분석 데이터 저장 실패 budgetId={}", budget.getId(), ex);
            }
        } catch (Exception e) {
            log.warn("AI 필드 백필 실패 userId={} yearMonth={}", userId, yearMonth, e);
            // 실패해도 재시도 방지
            budget.updateAiTotalBudget(budget.getTotalBudget());
        }
    }

    /** 프론트에서 수정한 카테고리 금액을 일괄 저장하고 총액을 재계산한 뒤 요약을 반환한다. */
    @Transactional
    public BudgetSummaryResponse saveCategories(
            Long userId, LocalDate yearMonth, List<BudgetCategoryUpdateRequest.CategoryAmount> categories) {
        Budget budget = budgetRepository
                .findByUserIdAndYearMonth(userId, yearMonth)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.BUDGET_NOT_FOUND));

        Map<Integer, BudgetCategory> existingMap = new HashMap<>();
        for (BudgetCategory bc : budgetCategoryRepository.findByBudgetId(budget.getId())) {
            existingMap.put(bc.getCategoryId(), bc);
        }

        long totalDelta = 0;
        for (BudgetCategoryUpdateRequest.CategoryAmount item : categories) {
            BudgetCategory bc = existingMap.get(item.categoryId());
            if (bc == null) {
                throw new GeneralException(GeneralErrorCode.BUDGET_CATEGORY_NOT_FOUND);
            }
            // 레거시 데이터: aiAmount가 null이면 수정 전 현재 금액으로 백필
            if (bc.getAiAmount() == null) {
                bc.updateAiAmount(bc.getAmount());
            }
            if (bc.getAmount() != item.amount()) {
                totalDelta += item.amount() - bc.getAmount();
                bc.modifyAmount(item.amount());
            }
        }

        // 총액 재계산 (저축액 등 카테고리 외 금액 보존)
        budget.updateTotalBudget(Math.addExact(budget.getTotalBudget(), totalDelta));

        return getBudgetSummary(userId, yearMonth);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long userId, LocalDate yearMonth, String errorMessage) {
        try {
            BudgetPlanRequest req = budgetPlanRequestRepository
                    .findByUserIdAndYearMonth(userId, yearMonth)
                    .orElse(null);
            if (req != null) {
                req.markFailed(errorMessage);
                budgetPlanRequestRepository.save(req);
            }
        } catch (Exception e) {
            log.error("markFailed 실패 userId={}", userId, e);
        }
    }

    private int genderCode(String gender) {
        if (gender == null) return 0;
        return switch (gender.toUpperCase()) {
            case "MALE" -> 1;
            case "FEMALE" -> 2;
            default -> 0;
        };
    }

    private int boolToCode(Boolean value) {
        if (value == null) return 0;
        return value ? 1 : 2;
    }
}
