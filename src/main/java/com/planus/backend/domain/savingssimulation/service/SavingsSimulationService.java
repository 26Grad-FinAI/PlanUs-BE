package com.planus.backend.domain.savingssimulation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.ai.EnrichedAction;
import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.BudgetProjector;
import com.planus.backend.domain.aifeedback.core.Categories;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.entity.UserReaction;
import com.planus.backend.domain.aifeedback.repository.AiFeedbackRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.aifeedback.repository.UserReactionRepository;
import com.planus.backend.domain.aifeedback.service.AiFeedbackService;
import com.planus.backend.domain.savingssimulation.core.SavingsMethodAnalyzer;
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
    private final SavingSimulationRepository simulationRepo;
    private final UserReactionRepository reactionRepo;
    private final AiFeedbackProperties props;
    private final AiFeedbackService feedbackService;
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
        AiFeedback feedback = loadFeedback(userId, yearMonth);
        LocalDate monthStart = parseYearMonth(yearMonth);
        CurrentActions current = currentActions(userId, feedback, yearMonth);
        List<EnrichedAction> enriched = current.actions();

        Set<Integer> rejected = rejectedCategories(userId);
        List<SuggestResponse.ActionCard> cards = enriched.stream()
                .filter(a -> a.reductionWon() >= MIN_REDUCTION_WON)
                .sorted(Comparator.comparingDouble(EnrichedAction::feasibilityScore)
                        .reversed())
                .map(a -> toCard(userId, monthStart, a, rejected, current))
                .toList();

        long totalReduction = cards.stream()
                .mapToLong(SuggestResponse.ActionCard::reductionWon)
                .sum();
        long totalMonthly = cards.stream()
                .mapToLong(SuggestResponse.ActionCard::monthlySavingWon)
                .sum();
        boolean applied = simulationRepo.existsByUserIdAndFeedbackIdAndIsAppliedTrue(userId, feedback.getId());
        return new SuggestResponse(
                feedback.getId(),
                applied,
                cards,
                totalReduction,
                totalMonthly,
                totalMonthly * 12,
                current.comparison());
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

        AiFeedback feedback = loadRequestedFeedback(userId, request.feedbackId(), request.yearMonth());
        CurrentActions current = currentActions(userId, feedback, request.yearMonth());
        Map<Integer, EnrichedAction> actionMap =
                current.actions().stream().collect(Collectors.toMap(EnrichedAction::categoryId, a -> a, (a, b) -> a));

        // 선택된 카테고리 검증 (중복 제거)
        List<SuggestResponse.ActionCard> selectedCards = new ArrayList<>();
        Set<Integer> rejected = rejectedCategories(userId);
        for (int catId : request.selectedCategoryIds().stream().distinct().toList()) {
            EnrichedAction action = actionMap.get(catId);
            if (action == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_CATEGORY_SELECTION);
            }
            selectedCards.add(toCard(userId, parseYearMonth(request.yearMonth()), action, rejected, current));
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

        AiFeedback feedback = loadRequestedFeedback(userId, request.feedbackId(), request.yearMonth());
        Map<Integer, EnrichedAction> actionMap =
                currentActions(userId, feedback, request.yearMonth()).actions().stream()
                        .collect(Collectors.toMap(EnrichedAction::categoryId, a -> a, (a, b) -> a));

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

        // 총 예산 갱신. 이 API는 예산을 줄이기만 하므로 가용예산 초과 검사를 하지 않는다 —
        // 예산 총액은 소득·고정지출과 무관하게 정해져 있어, 검사하면 이미 기준을 넘은 사용자는 아무리 줄여도 거부된다 (#129)
        long newTotal = budget.getTotalBudget() - totalReduction;
        long available = user.availableBudget();
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

        // simulate와 같은 지출 기준 저축액 (수입 − 고정지출 − 예상 변동지출) + 실제 적용한 감축액.
        // 예산 총액은 소득과 무관하게 정해져 있어 예산에서 역산하면 simulate와 어긋난다 (#129)
        long currentSavings = available + user.getMonthlySavingsGoal() - expectedVariableSpend(userId, monthStart);
        long monthlySavings = Math.max(0, currentSavings + totalReduction);
        return new ApplyResponse(true, changes, newTotal, monthlySavings);
    }

    // ── 내부 메서드 ──

    /** 월 절약액 기준: 직전 몇 개월의 카테고리 월평균 변동지출. */
    private static final int HABIT_BASE_MONTHS = 3;

    /**
     * 시뮬레이션에서 보여줄 절약 액션과 피드백과의 비교 (#127).
     *
     * @param actions            보여줄 액션. 진행 중인 달이면 오늘 기준으로 다시 계산한 것, 아니면 피드백에 저장된 것
     * @param feedbackReductions 피드백 시점의 카테고리별 감축 제안액. 비교할 수 없으면 null
     * @param comparison         피드백 이후 변화 한 줄. 비교할 수 없으면 null
     */
    private record CurrentActions(
            List<EnrichedAction> actions, Map<Integer, Long> feedbackReductions, String comparison) {
        String sourceOf(int categoryId) {
            return feedbackReductions == null || feedbackReductions.containsKey(categoryId) ? "FEEDBACK" : "NEW";
        }

        Long feedbackReductionOf(int categoryId) {
            return feedbackReductions == null ? null : feedbackReductions.get(categoryId);
        }
    }

    /**
     * 오늘 기준 절약 액션. 피드백은 주 단위로 저장돼 일요일에는 6일 전 기준(남은 일수·지출·초과 카테고리)이 되므로, 진행 중인 달은 피드백 분석을
     * 오늘 날짜로 다시 계산한다. 요청마다 LLM을 호출하지 않는다 — 문구는 피드백에 저장된 것을 같은 카테고리에 이어 쓰고, 새로 생긴 카테고리는 문구 없이
     * 코드로 계산한 방법·실현 가능성만 보여준다.
     *
     * <p>이번 달이 아니거나 다시 계산할 수 없으면 피드백에 저장된 액션을 그대로 쓴다.
     */
    private CurrentActions currentActions(Long userId, AiFeedback feedback, String yearMonth) {
        List<EnrichedAction> saved = parseEnrichedActions(feedback, yearMonth);
        LocalDate today = LocalDate.now(clock);
        if (!YearMonth.from(today).equals(YearMonth.parse(yearMonth, YM_FORMATTER))) {
            return new CurrentActions(saved, null, null);
        }
        AiFeedbackService.SavingsActionsAsOf now;
        try {
            now = feedbackService.computeActionsAsOf(userId, today);
        } catch (Exception e) {
            log.warn("절약 액션 재계산 실패 — 피드백 저장분 사용: userId={}", userId, e);
            return new CurrentActions(saved, null, null);
        }
        if (now == null) return new CurrentActions(saved, null, null);

        Map<Integer, EnrichedAction> savedByCat =
                saved.stream().collect(Collectors.toMap(EnrichedAction::categoryId, a -> a, (a, b) -> a));
        List<EnrichedAction> actions = new ArrayList<>();
        for (var plan : now.actions()) {
            EnrichedAction old = savedByCat.get(plan.categoryId());
            actions.add(new EnrichedAction(
                    plan.categoryId(),
                    plan.reductionWon(),
                    plan.dominantFactor(),
                    plan.feasibilityScore(),
                    old != null ? old.actionType() : null,
                    old != null ? old.currentBehavior() : null,
                    old != null ? old.targetBehavior() : null,
                    old != null ? old.contextInsight() : null,
                    old != null ? old.behaviorFeasibility() : plan.feasibilityScore()));
        }
        Map<Integer, Long> feedbackReductions = new HashMap<>();
        saved.forEach(a -> feedbackReductions.put(a.categoryId(), a.reductionWon()));
        return new CurrentActions(actions, feedbackReductions, comparisonLine(feedback, now.savingsGapWon()));
    }

    /** "11/9 피드백 이후 저축 목표까지 부족액 29만 원 → 33만 원". 피드백에 부족액이 저장돼 있지 않으면 null. */
    private String comparisonLine(AiFeedback feedback, long nowGap) {
        try {
            Map<String, Object> payload = objectMapper.readValue(feedback.getPayload(), new TypeReference<>() {});
            if (!(payload.get("savingsGapWon") instanceof Number n)) return null;
            long then = n.longValue();
            LocalDate d = feedback.getPeriodEnd();
            String when = d == null ? "" : d.getMonthValue() + "/" + d.getDayOfMonth() + " ";
            if (then == nowGap) return when + "피드백 이후 저축 목표까지 부족액이 그대로 " + won(nowGap) + "이에요";
            return String.format("%s피드백 이후 저축 목표까지 부족액 %s → %s", when, won(then), won(nowGap));
        } catch (Exception e) {
            return null;
        }
    }

    /** 만 원 단위로 읽기 쉽게. */
    private static String won(long w) {
        if (w == 0) return "0원";
        return w >= 10_000 ? String.format("%,d만 원", Math.round(w / 10_000.0)) : String.format("%,d원", w);
    }

    /** 지금 효력 있는, 줄이고 싶지 않다고 한 카테고리 (#141 만료 기준과 같다). */
    private Set<Integer> rejectedCategories(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return reactionRepo.findByUserId(userId).stream()
                .filter(r -> r.getRejectionReason() != null
                        && r.getCategoryId() != null
                        && r.isActiveAt(now, props.getRejectionExpiryMonths()))
                .map(UserReaction::getCategoryId)
                .collect(Collectors.toSet());
    }

    private SuggestResponse.ActionCard toCard(
            Long userId, LocalDate monthStart, EnrichedAction a, Set<Integer> rejected, CurrentActions current) {
        CategoryData data = loadCategoryData(userId, monthStart, a.categoryId());
        long monthly = monthlySaving(data, a.reductionWon());
        SavingsMethodAnalyzer.Plan plan = SavingsMethodAnalyzer.analyze(new SavingsMethodAnalyzer.Input(
                a.categoryId(),
                a.dominantFactor(),
                a.reductionWon(),
                data.upTo(),
                data.thisMonth(),
                data.past(),
                data.remaining(),
                rejected.contains(a.categoryId())));
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
                monthly * 12,
                plan.method(),
                plan.level().name(),
                plan.reasons(),
                current.sourceOf(a.categoryId()),
                current.feedbackReductionOf(a.categoryId()));
    }

    /** 카테고리 하나의 이번 달·직전 3개월 변동 지출과 남은 기간 예상 지출. */
    private record CategoryData(
            LocalDate upTo,
            List<Expense> thisMonth,
            List<Expense> past,
            long mtd,
            long remaining,
            YearMonth ym,
            Map<YearMonth, Long> byMonth) {}

    private CategoryData loadCategoryData(Long userId, LocalDate monthStart, int categoryId) {
        YearMonth ym = YearMonth.from(monthStart);
        LocalDate today = LocalDate.now(clock);
        LocalDate baseStart = monthStart.minusMonths(HABIT_BASE_MONTHS);
        LocalDate upTo = today.isBefore(ym.atEndOfMonth()) ? today : ym.atEndOfMonth();
        List<Expense> all = expenseRepo
                .findByUserIdAndCategoryIdAndExpenseDateBetween(
                        userId, categoryId, baseStart.atStartOfDay(), upTo.atTime(LocalTime.MAX))
                .stream()
                .filter(Expense::isVariable)
                .toList();
        List<Expense> thisMonth =
                all.stream().filter(e -> !e.getDate().isBefore(monthStart)).toList();
        List<Expense> past =
                all.stream().filter(e -> e.getDate().isBefore(monthStart)).toList();

        Map<YearMonth, Long> byMonth = new HashMap<>();
        for (Expense e : all) byMonth.merge(YearMonth.from(e.getDate()), e.getAmount(), Long::sum);
        long mtd = byMonth.getOrDefault(ym, 0L);
        long remaining = 0;
        if (YearMonth.from(today).equals(ym)) {
            long prev = byMonth.getOrDefault(ym.minusMonths(1), 0L);
            double priorRate = prev > 0 ? (double) prev / ym.minusMonths(1).lengthOfMonth() : 0.0;
            remaining = BudgetProjector.remainingCategoryExpected(
                    mtd, today.getDayOfMonth(), ym.lengthOfMonth(), priorRate, null, null);
        }
        return new CategoryData(upTo, thisMonth, past, mtd, remaining, ym, byMonth);
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
    private long monthlySaving(CategoryData d, long reductionWon) {
        List<Long> past = new ArrayList<>();
        for (int i = 1; i <= HABIT_BASE_MONTHS; i++) {
            Long v = d.byMonth().get(d.ym().minusMonths(i));
            if (v != null && v > 0) past.add(v);
        }
        long base = past.isEmpty()
                ? d.mtd() + d.remaining()
                : Math.round(past.stream().mapToLong(Long::longValue).average().orElse(0));
        if (base <= 0) return 0;

        double rate = d.remaining() > 0 ? (double) reductionWon / d.remaining() : (double) reductionWon / base;
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

    private AiFeedback loadFeedback(Long userId, String yearMonth) {
        LocalDate monthStart = parseYearMonth(yearMonth);

        List<AiFeedback> feedbacks = feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(userId, "WEEKLY");

        return feedbacks.stream()
                .filter(f -> f.getYearMonth().equals(monthStart))
                .findFirst()
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.FEEDBACK_NOT_FOUND));
    }

    private AiFeedback loadRequestedFeedback(Long userId, Long feedbackId, String yearMonth) {
        LocalDate monthStart = parseYearMonth(yearMonth);
        return feedbackRepo
                .findById(feedbackId)
                .filter(feedback -> userId.equals(feedback.getUserId())
                        && monthStart.equals(feedback.getYearMonth())
                        && "WEEKLY".equals(feedback.getPeriodType()))
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
