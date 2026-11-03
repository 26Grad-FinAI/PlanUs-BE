package com.planus.backend.domain.savingssimulation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.ai.EnrichedAction;
import com.planus.backend.domain.aifeedback.core.Categories;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.repository.AiFeedbackRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.savingssimulation.dto.*;
import com.planus.backend.domain.savingssimulation.dto.SimulateResponse.DisplayType;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 절약 시뮬레이션 비즈니스 로직.
 *
 * <p>피드백 payload의 enrichedActions를 기반으로 절약 제안 조회, 시뮬레이션, 예산 반영을 수행한다.
 */
@Service
@RequiredArgsConstructor
public class SavingsSimulationService {

    private static final Logger log = LoggerFactory.getLogger(SavingsSimulationService.class);
    private static final long MIN_REDUCTION_WON = 1_000;
    private static final long MIN_BUDGET_WON = 10_000;
    private static final DateTimeFormatter YM_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AiFeedbackRepository feedbackRepo;
    private final BudgetRepository budgetRepo;
    private final BudgetCategoryRepository budgetCatRepo;
    private final ExpenseRepository expenseRepo;
    private final UserAccountRepository userRepo;
    private final ObjectMapper objectMapper;

    /**
     * 해당 월의 절약 제안 목록을 조회한다.
     *
     * @param userId    사용자 ID
     * @param yearMonth 대상 연월 (yyyy-MM)
     * @return 절약 제안 응답
     */
    public SuggestResponse suggest(Long userId, String yearMonth) {
        List<EnrichedAction> enriched = loadEnrichedActions(userId, yearMonth);

        List<SuggestResponse.ActionCard> cards = enriched.stream()
                .filter(a -> a.reductionWon() >= MIN_REDUCTION_WON)
                .sorted(Comparator.comparingDouble(EnrichedAction::feasibilityScore)
                        .reversed())
                .map(a -> new SuggestResponse.ActionCard(
                        a.categoryId(),
                        Categories.name(a.categoryId()),
                        a.reductionWon(),
                        a.actionType(),
                        a.currentBehavior(),
                        a.targetBehavior(),
                        a.contextInsight(),
                        a.feasibilityScore()))
                .toList();

        long totalReduction = cards.stream()
                .mapToLong(SuggestResponse.ActionCard::reductionWon)
                .sum();
        return new SuggestResponse(cards, totalReduction);
    }

    /**
     * 선택한 카테고리의 절약 효과를 시뮬레이션한다.
     *
     * @param userId  사용자 ID
     * @param request 시뮬레이션 요청
     * @return 시뮬레이션 결과
     */
    public SimulateResponse simulate(Long userId, SimulateRequest request) {
        UserAccount user =
                userRepo.findById(userId).orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        List<EnrichedAction> enriched = loadEnrichedActions(userId, request.yearMonth());
        Map<Integer, EnrichedAction> actionMap =
                enriched.stream().collect(Collectors.toMap(EnrichedAction::categoryId, a -> a, (a, b) -> a));

        // 선택된 카테고리 검증
        List<SuggestResponse.ActionCard> selectedCards = new ArrayList<>();
        for (int catId : request.selectedCategoryIds()) {
            EnrichedAction action = actionMap.get(catId);
            if (action == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_CATEGORY_SELECTION);
            }
            selectedCards.add(new SuggestResponse.ActionCard(
                    action.categoryId(),
                    Categories.name(action.categoryId()),
                    action.reductionWon(),
                    action.actionType(),
                    action.currentBehavior(),
                    action.targetBehavior(),
                    action.contextInsight(),
                    action.feasibilityScore()));
        }

        long reductionTotal = selectedCards.stream()
                .mapToLong(SuggestResponse.ActionCard::reductionWon)
                .sum();

        // 가용예산 및 현재 소비 계산
        long available = user.availableBudget();
        LocalDate monthStart = parseYearMonth(request.yearMonth());
        LocalDate monthEnd = YearMonth.from(monthStart).atEndOfMonth();
        LocalDateTime from = monthStart.atStartOfDay();
        LocalDateTime to = monthEnd.atTime(LocalTime.MAX);
        long currentSpend = expenseRepo.sumVariableExpensesByPeriod(userId, from, to);

        long currentSavings = available - currentSpend;
        long projectedSavings = currentSavings + reductionTotal;

        // 화면 분기 결정
        long savingsGoal = user.getMonthlySavingsGoal();
        DisplayType displayType;
        String message;

        if (currentSavings < 0) {
            // 현재 적자
            displayType = DisplayType.DEFICIT;
            if (projectedSavings >= 0) {
                message = String.format("매달 %,d원 초과 지출이 절약 시 균형에 도달합니다", -currentSavings);
            } else {
                message = String.format("매달 %,d원 초과 지출이 %,d원으로 줄어듭니다", -currentSavings, -projectedSavings);
            }
        } else if (savingsGoal > 0 && currentSavings < savingsGoal) {
            // 저축 목표 미달
            if (projectedSavings >= savingsGoal) {
                displayType = DisplayType.RECOVER;
                message = String.format("매달 %,d원 부족했던 저축 목표를 달성할 수 있습니다", savingsGoal - currentSavings);
            } else {
                displayType = DisplayType.RECOVER;
                message = String.format(
                        "저축 목표까지 %,d원 부족이 %,d원으로 줄어듭니다", savingsGoal - currentSavings, savingsGoal - projectedSavings);
            }
        } else if (savingsGoal > 0) {
            // 저축 목표 있고 충분 — 달성 단축
            displayType = DisplayType.GOAL;
            long goalAmount = savingsGoal > 0 ? savingsGoal * 12 : 0; // 연간 저축 목표
            if (goalAmount > 0 && currentSavings > 0) {
                long currentMonths = (goalAmount + currentSavings - 1) / currentSavings;
                long projectedMonths =
                        projectedSavings > 0 ? (goalAmount + projectedSavings - 1) / projectedSavings : currentMonths;
                message = String.format("저축 목표 달성이 %d개월에서 %d개월로 단축됩니다", currentMonths, projectedMonths);
            } else {
                message = String.format("연 %,d원 추가 절약이 가능합니다", reductionTotal * 12);
            }
        } else {
            // 여유 있음
            displayType = DisplayType.SURPLUS;
            message = String.format("연 %,d원 추가 절약이 가능합니다", reductionTotal * 12);
        }

        return new SimulateResponse(
                displayType, currentSavings, projectedSavings, reductionTotal, message, selectedCards);
    }

    /**
     * 시뮬레이션 결과를 예산에 반영한다.
     *
     * @param userId  사용자 ID
     * @param request 예산 반영 요청
     * @return 반영 결과
     */
    @Transactional
    public ApplyResponse apply(Long userId, ApplyRequest request) {
        UserAccount user =
                userRepo.findById(userId).orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        List<EnrichedAction> enriched = loadEnrichedActions(userId, request.yearMonth());
        Map<Integer, EnrichedAction> actionMap =
                enriched.stream().collect(Collectors.toMap(EnrichedAction::categoryId, a -> a, (a, b) -> a));

        LocalDate monthStart = parseYearMonth(request.yearMonth());
        Budget budget = budgetRepo
                .findByUserIdAndYearMonth(userId, monthStart)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.BUDGET_NOT_FOUND));

        List<BudgetCategory> categories = budgetCatRepo.findByBudgetId(budget.getId());
        Map<Integer, BudgetCategory> catMap =
                categories.stream().collect(Collectors.toMap(BudgetCategory::getCategoryId, bc -> bc, (a, b) -> a));

        List<ApplyResponse.CategoryChange> changes = new ArrayList<>();
        long totalReduction = 0;

        for (int catId : request.selectedCategoryIds()) {
            EnrichedAction action = actionMap.get(catId);
            if (action == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_CATEGORY_SELECTION);
            }

            BudgetCategory bc = catMap.get(catId);
            if (bc == null) {
                throw new GeneralException(GeneralErrorCode.BUDGET_CATEGORY_NOT_FOUND);
            }

            long before = bc.getAmount();
            long newAmount = Math.max(before - action.reductionWon(), MIN_BUDGET_WON);
            long actualReduction = before - newAmount;

            bc.modifyAmount(newAmount);
            changes.add(new ApplyResponse.CategoryChange(catId, before, newAmount));
            totalReduction += actualReduction;
        }

        // 총 예산 갱신
        long newTotal = budget.getTotalBudget() - totalReduction;
        long available = user.availableBudget();
        if (newTotal > available + user.getMonthlySavingsGoal()) {
            throw new GeneralException(GeneralErrorCode.BUDGET_EXCEEDED);
        }
        budget.updateTotalBudget(newTotal);

        long monthlySavings = available - newTotal + user.getMonthlySavingsGoal();
        return new ApplyResponse(true, changes, newTotal, monthlySavings);
    }

    // ── 내부 메서드 ──

    private List<EnrichedAction> loadEnrichedActions(Long userId, String yearMonth) {
        LocalDate monthStart = parseYearMonth(yearMonth);

        // 해당 월의 최신 WEEKLY 피드백에서 payload 추출
        List<AiFeedback> feedbacks = feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(userId, "WEEKLY");

        AiFeedback latest = feedbacks.stream()
                .filter(f -> f.getYearMonth().equals(monthStart))
                .findFirst()
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.FEEDBACK_NOT_FOUND));

        String payload = latest.getPayload();
        if (payload == null || payload.isBlank()) {
            throw new GeneralException(GeneralErrorCode.NO_ENRICHED_ACTIONS);
        }

        try {
            Map<String, Object> payloadMap = objectMapper.readValue(payload, new TypeReference<>() {});

            // enrichedActions 시도
            if (payloadMap.containsKey("enrichedActions")) {
                String enrichedJson = objectMapper.writeValueAsString(payloadMap.get("enrichedActions"));
                return objectMapper.readValue(enrichedJson, new TypeReference<>() {});
            }

            // fallback: 기존 actions를 기본 변환
            if (payloadMap.containsKey("actions")) {
                String actionsJson = objectMapper.writeValueAsString(payloadMap.get("actions"));
                List<Map<String, Object>> actionMaps = objectMapper.readValue(actionsJson, new TypeReference<>() {});
                return actionMaps.stream()
                        .map(m -> new EnrichedAction(
                                ((Number) m.get("categoryId")).intValue(),
                                ((Number) m.get("reductionWon")).longValue(),
                                (String) m.get("dominantFactor"),
                                ((Number) m.get("feasibilityScore")).doubleValue(),
                                null,
                                null,
                                null,
                                null,
                                ((Number) m.get("feasibilityScore")).doubleValue()))
                        .toList();
            }

            throw new GeneralException(GeneralErrorCode.NO_ENRICHED_ACTIONS);
        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            log.warn("피드백 payload 파싱 실패: userId={}, yearMonth={}", userId, yearMonth, e);
            throw new GeneralException(GeneralErrorCode.NO_ENRICHED_ACTIONS);
        }
    }

    private LocalDate parseYearMonth(String yearMonth) {
        YearMonth ym = YearMonth.parse(yearMonth, YM_FORMATTER);
        return ym.atDay(1);
    }
}
