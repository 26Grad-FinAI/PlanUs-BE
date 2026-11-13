package com.planus.backend.domain.savingssimulation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.ai.EnrichedAction;
import com.planus.backend.domain.aifeedback.core.BudgetProjector;
import com.planus.backend.domain.aifeedback.core.Categories;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.AiFeedbackRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.savingssimulation.dto.*;
import com.planus.backend.domain.savingssimulation.dto.SimulateResponse.DisplayType;
import com.planus.backend.domain.savingssimulation.entity.SavingSimulation;
import com.planus.backend.domain.savingssimulation.entity.SavingSimulationItem;
import com.planus.backend.domain.savingssimulation.repository.SavingSimulationRepository;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.Clock;
import java.time.LocalDate;
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
    private final SavingSimulationRepository simulationRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 해당 월의 절약 제안 목록을 조회한다.
     *
     * @param userId    사용자 ID
     * @param yearMonth 대상 연월 (yyyy-MM)
     * @return 절약 제안 응답
     */
    public SuggestResponse suggest(Long userId, String yearMonth) {
        List<EnrichedAction> enriched = loadEnrichedActions(userId, yearMonth);
        LocalDate monthStart = parseYearMonth(yearMonth);

        List<SuggestResponse.ActionCard> cards = enriched.stream()
                .filter(a -> a.reductionWon() >= MIN_REDUCTION_WON)
                .sorted(Comparator.comparingDouble(EnrichedAction::feasibilityScore)
                        .reversed())
                .map(a -> toCard(userId, monthStart, a))
                .toList();

        long totalReduction = cards.stream()
                .mapToLong(SuggestResponse.ActionCard::reductionWon)
                .sum();
        long totalMonthly = cards.stream()
                .mapToLong(SuggestResponse.ActionCard::monthlySavingWon)
                .sum();
        return new SuggestResponse(cards, totalReduction, totalMonthly, totalMonthly * 12);
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

        // 선택된 카테고리 검증 (중복 제거)
        List<SuggestResponse.ActionCard> selectedCards = new ArrayList<>();
        for (int catId : request.selectedCategoryIds().stream().distinct().toList()) {
            EnrichedAction action = actionMap.get(catId);
            if (action == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_CATEGORY_SELECTION);
            }
            selectedCards.add(toCard(userId, parseYearMonth(request.yearMonth()), action));
        }

        long reductionTotal = selectedCards.stream()
                .mapToLong(SuggestResponse.ActionCard::reductionWon)
                .sum();
        long monthlySaving = selectedCards.stream()
                .mapToLong(SuggestResponse.ActionCard::monthlySavingWon)
                .sum();
        long annualSaving = monthlySaving * 12;

        // 이번 달 저축액 = 수입 − 고정지출 − 변동지출.
        // 가용예산(availableBudget)은 이미 저축 목표를 뺀 값이므로 다시 더한다 — 빼면 저축 목표를 두 번 차감해,
        // 목표를 채우고 남는데도 "저축 목표 미달"로 분류된다 (#127)
        long savingsGoal = user.getMonthlySavingsGoal();
        long available = user.availableBudget();
        LocalDate monthStart = parseYearMonth(request.yearMonth());
        long expectedSpend = expectedVariableSpend(userId, monthStart);

        long currentSavings = available + savingsGoal - expectedSpend;
        long projectedSavings = currentSavings + reductionTotal;

        // 화면 분기 결정
        DisplayType displayType;
        String message;

        if (currentSavings < 0) {
            // 이번 달 적자 (수입 − 고정지출보다 많이 씀)
            displayType = DisplayType.DEFICIT;
            if (projectedSavings >= 0) {
                message = String.format("이번 달 %,d원 적자가 절약 시 균형에 도달합니다", -currentSavings);
            } else {
                message = String.format("이번 달 %,d원 적자가 %,d원으로 줄어듭니다", -currentSavings, -projectedSavings);
            }
        } else if (savingsGoal > 0 && currentSavings < savingsGoal) {
            // 저축 목표 미달
            displayType = DisplayType.RECOVER;
            if (projectedSavings >= savingsGoal) {
                message = String.format("이번 달 %,d원 부족했던 저축 목표를 달성할 수 있습니다", savingsGoal - currentSavings);
            } else {
                message = String.format(
                        "이번 달 저축 목표까지 %,d원 부족이 %,d원으로 줄어듭니다",
                        savingsGoal - currentSavings, savingsGoal - projectedSavings);
            }
        } else if (savingsGoal > 0) {
            // 저축 목표 있고 충분 — 달성 단축
            displayType = DisplayType.GOAL;
            long goalAmount = savingsGoal * 12; // 연간 저축 목표
            long habitSavings = currentSavings + monthlySaving; // 습관을 유지했을 때 월 저축액
            if (currentSavings > 0) {
                long currentMonths = (goalAmount + currentSavings - 1) / currentSavings;
                long projectedMonths = (goalAmount + habitSavings - 1) / habitSavings;
                message = String.format("저축 목표 달성이 %d개월에서 %d개월로 단축됩니다", currentMonths, projectedMonths);
            } else {
                message = String.format("월 %,d원, 1년 %,d원을 더 저축할 수 있습니다", monthlySaving, annualSaving);
            }
        } else {
            // 여유 있음 — 연 절약액은 습관 기준 월 절약액 × 12 (#127: 이번 달 남은 기간 금액 × 12가 아님)
            displayType = DisplayType.SURPLUS;
            message = String.format("월 %,d원, 1년 %,d원을 더 저축할 수 있습니다", monthlySaving, annualSaving);
        }

        return new SimulateResponse(
                displayType,
                currentSavings,
                projectedSavings,
                reductionTotal,
                monthlySaving,
                annualSaving,
                message,
                selectedCards);
    }

    /**
     * 시뮬레이션 결과를 예산에 반영한다.
     *
     * <p>동일 피드백에 대해 이미 적용된 시뮬레이션이 있으면 {@code ALREADY_APPLIED} 예외를 던져 멱등성을 보장한다.
     *
     * @param userId  사용자 ID
     * @param request 예산 반영 요청
     * @return 반영 결과
     */
    @Transactional
    public ApplyResponse apply(Long userId, ApplyRequest request) {
        UserAccount user =
                userRepo.findById(userId).orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        AiFeedback feedback = loadFeedback(userId, request.yearMonth());
        List<EnrichedAction> enriched = parseEnrichedActions(feedback, request.yearMonth());
        Map<Integer, EnrichedAction> actionMap =
                enriched.stream().collect(Collectors.toMap(EnrichedAction::categoryId, a -> a, (a, b) -> a));

        // 멱등성 검사: 동일 피드백에 대해 이미 적용되었는지 확인
        simulationRepo
                .findByUserIdAndFeedbackIdAndIsAppliedTrue(userId, feedback.getId())
                .ifPresent(existing -> {
                    throw new GeneralException(GeneralErrorCode.ALREADY_APPLIED);
                });

        LocalDate monthStart = parseYearMonth(request.yearMonth());
        Budget budget = budgetRepo
                .findByUserIdAndYearMonth(userId, monthStart)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.BUDGET_NOT_FOUND));

        List<BudgetCategory> categories = budgetCatRepo.findByBudgetId(budget.getId());
        Map<Integer, BudgetCategory> catMap =
                categories.stream().collect(Collectors.toMap(BudgetCategory::getCategoryId, bc -> bc, (a, b) -> a));

        List<ApplyResponse.CategoryChange> changes = new ArrayList<>();
        long totalReduction = 0;

        for (int catId : request.selectedCategoryIds().stream().distinct().toList()) {
            EnrichedAction action = actionMap.get(catId);
            if (action == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_CATEGORY_SELECTION);
            }

            BudgetCategory bc = catMap.get(catId);
            if (bc == null) {
                throw new GeneralException(GeneralErrorCode.BUDGET_CATEGORY_NOT_FOUND);
            }

            long before = bc.getAmount();
            long newAmount = Math.min(before, Math.max(before - action.reductionWon(), MIN_BUDGET_WON));
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

        // 적용 이력 저장
        SavingSimulation simulation = SavingSimulation.builder()
                .userId(userId)
                .feedbackId(feedback.getId())
                .yearMonth(monthStart)
                .type("BUDGET_REDUCTION")
                .isApplied(true)
                .totalSavingAmount(totalReduction)
                .build();

        for (ApplyResponse.CategoryChange change : changes) {
            long savingAmount = change.before() - change.after();
            SavingSimulationItem item = SavingSimulationItem.builder()
                    .categoryId((long) change.categoryId())
                    .savingAmount(savingAmount)
                    .annualSaving(savingAmount * 12)
                    .build();
            simulation.addItem(item);
        }
        simulationRepo.save(simulation);

        long monthlySavings = available - newTotal + user.getMonthlySavingsGoal();
        return new ApplyResponse(true, changes, newTotal, monthlySavings);
    }

    // ── 내부 메서드 ──

    /** 월 절약액 기준: 직전 몇 개월의 카테고리 월평균 변동지출. */
    private static final int HABIT_BASE_MONTHS = 3;

    private SuggestResponse.ActionCard toCard(Long userId, LocalDate monthStart, EnrichedAction a) {
        long monthly = monthlySaving(userId, monthStart, a.categoryId(), a.reductionWon());
        return new SuggestResponse.ActionCard(
                a.categoryId(),
                Categories.name(a.categoryId()),
                a.reductionWon(),
                a.actionType(),
                a.currentBehavior(),
                a.targetBehavior(),
                a.contextInsight(),
                a.feasibilityScore(),
                monthly,
                monthly * 12);
    }

    /**
     * 같은 습관을 한 달 내내 유지했을 때의 월 절약액 (#127).
     *
     * <p>절약 액션 금액(reductionWon)은 이번 달 남은 기간에 줄일 금액이라, 남은 일수에 따라 9,718원처럼 작거나 월초엔 크다. 그대로 ×12 하면
     * 연 절약액이 왜곡되므로 "남은 기간 예상 지출 대비 줄이는 비율"을 구해 그 카테고리의 월평균 지출에 적용한다.
     * <pre>
     * 비율     = reductionWon ÷ 이번 달 남은 기간 예상 지출 (상한 1)
     * 월 절약액 = 비율 × 직전 3개월 월평균 지출 (기록이 없으면 이번 달 예상 지출)
     * </pre>
     */
    private long monthlySaving(Long userId, LocalDate monthStart, int categoryId, long reductionWon) {
        YearMonth ym = YearMonth.from(monthStart);
        LocalDate today = LocalDate.now(clock);
        LocalDate baseStart = monthStart.minusMonths(HABIT_BASE_MONTHS);
        LocalDate upTo = today.isBefore(ym.atEndOfMonth()) ? today : ym.atEndOfMonth();
        List<Expense> expenses = expenseRepo.findByUserIdAndCategoryIdAndExpenseDateBetween(
                userId, categoryId, baseStart.atStartOfDay(), upTo.atTime(LocalTime.MAX));

        Map<YearMonth, Long> byMonth = new HashMap<>();
        for (Expense e : expenses) {
            if (e.isVariable()) byMonth.merge(YearMonth.from(e.getDate()), e.getAmount(), Long::sum);
        }
        long mtd = byMonth.getOrDefault(ym, 0L);
        long remaining = 0;
        if (YearMonth.from(today).equals(ym)) {
            long prev = byMonth.getOrDefault(ym.minusMonths(1), 0L);
            double priorRate = prev > 0 ? (double) prev / ym.minusMonths(1).lengthOfMonth() : 0.0;
            remaining = BudgetProjector.remainingCategoryExpected(
                    mtd, today.getDayOfMonth(), ym.lengthOfMonth(), priorRate, null, null);
        }

        List<Long> past = new ArrayList<>();
        for (int i = 1; i <= HABIT_BASE_MONTHS; i++) {
            Long v = byMonth.get(ym.minusMonths(i));
            if (v != null && v > 0) past.add(v);
        }
        long base = past.isEmpty()
                ? mtd + remaining
                : Math.round(past.stream().mapToLong(Long::longValue).average().orElse(0));
        if (base <= 0) return 0;

        double rate = remaining > 0 ? (double) reductionWon / remaining : (double) reductionWon / base;
        return Math.round(Math.min(1.0, rate) * base);
    }

    /**
     * 이번 달 변동지출. 지난 달은 실제 합계, 진행 중인 달은 피드백과 같은 월말 예측(이번 달 일평균과 전월 일평균을 경과일 비율로 혼합).
     *
     * <p>지금까지 쓴 돈만 쓰면 월초에는 저축액이 크게 잡혀, 같은 화면의 피드백("이대로면 월말에 N원 초과")과 반대로 "여유"가 나온다 (#127).
     */
    private long expectedVariableSpend(Long userId, LocalDate monthStart) {
        YearMonth ym = YearMonth.from(monthStart);
        LocalDate today = LocalDate.now(clock);
        LocalDate monthEnd = ym.atEndOfMonth();
        if (today.isBefore(monthStart)) return 0;
        LocalDate upTo = today.isBefore(monthEnd) ? today : monthEnd;
        long mtd =
                expenseRepo.sumVariableExpensesByPeriod(userId, monthStart.atStartOfDay(), upTo.atTime(LocalTime.MAX));
        if (!YearMonth.from(today).equals(ym)) return mtd;

        LocalDate prevStart = monthStart.minusMonths(1);
        long prev = expenseRepo.sumVariableExpensesByPeriod(
                userId, prevStart.atStartOfDay(), monthStart.minusDays(1).atTime(LocalTime.MAX));
        double priorDailyRate = prev > 0 ? (double) prev / prevStart.lengthOfMonth() : 0.0;
        // 카테고리 하나와 전체 변동지출에 같은 식을 쓴다
        return BudgetProjector.predictCategory(
                mtd, 0, today.getDayOfMonth(), ym.lengthOfMonth(), priorDailyRate, null, null);
    }

    private List<EnrichedAction> loadEnrichedActions(Long userId, String yearMonth) {
        AiFeedback feedback = loadFeedback(userId, yearMonth);
        return parseEnrichedActions(feedback, yearMonth);
    }

    private AiFeedback loadFeedback(Long userId, String yearMonth) {
        LocalDate monthStart = parseYearMonth(yearMonth);

        List<AiFeedback> feedbacks = feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(userId, "WEEKLY");

        return feedbacks.stream()
                .filter(f -> f.getYearMonth().equals(monthStart))
                .findFirst()
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.FEEDBACK_NOT_FOUND));
    }

    private List<EnrichedAction> parseEnrichedActions(AiFeedback feedback, String yearMonth) {
        String payload = feedback.getPayload();
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
            log.warn("피드백 payload 파싱 실패: userId={}, yearMonth={}", feedback.getUserId(), yearMonth, e);
            throw new GeneralException(GeneralErrorCode.NO_ENRICHED_ACTIONS);
        }
    }

    private LocalDate parseYearMonth(String yearMonth) {
        try {
            return YearMonth.parse(yearMonth, YM_FORMATTER).atDay(1);
        } catch (java.time.format.DateTimeParseException e) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST);
        }
    }
}
