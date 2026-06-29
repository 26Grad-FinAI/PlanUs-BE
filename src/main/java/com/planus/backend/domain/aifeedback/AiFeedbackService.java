package com.planus.backend.domain.aifeedback;

import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.*;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.entity.UserAccount;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.FeedbackContext;
import com.planus.backend.domain.aifeedback.rag.ActionService;
import com.planus.backend.domain.aifeedback.rag.HypothesisService;
import com.planus.backend.domain.aifeedback.rag.MemoVectorStore;
import com.planus.backend.domain.aifeedback.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;


/**
 * AI-01 주간 파이프라인 오케스트레이터.
 * [6] 예측·저축(1순위) → [4] 탐지(보조) → 분기 → [5] 원인 → [7] 액션 → 렌더 → 저장.
 * 데이터 조립부의 '남은 고정·예정 추정', '과거 일평균'은 근사이며 운영 데이터에 맞게 조정 필요(주석 TODO).
 */
@Service
public class AiFeedbackService {

    private final UserAccountRepository userRepo;
    private final ExpenseRepository expenseRepo;
    private final BudgetRepository budgetRepo;
    private final BudgetCategoryRepository budgetCatRepo;
    private final AiFeedbackRepository feedbackRepo;
    private final AnomalyDetector detector;
    private final BudgetProjector projector;
    private final HypothesisService hypothesis;
    private final ActionService action;
    private final MemoVectorStore memoStore;
    private final FeedbackRenderer renderer;
    private final AiFeedbackProperties p;
    private final Clock clock;

    public AiFeedbackService(UserAccountRepository userRepo, ExpenseRepository expenseRepo,
                             BudgetRepository budgetRepo, BudgetCategoryRepository budgetCatRepo,
                             AiFeedbackRepository feedbackRepo, AnomalyDetector detector,
                             BudgetProjector projector, HypothesisService hypothesis,
                             ActionService action, MemoVectorStore memoStore,
                             FeedbackRenderer renderer, AiFeedbackProperties p,
                             Clock clock) {
        this.userRepo = userRepo; this.expenseRepo = expenseRepo; this.budgetRepo = budgetRepo;
        this.budgetCatRepo = budgetCatRepo; this.feedbackRepo = feedbackRepo; this.detector = detector;
        this.projector = projector; this.hypothesis = hypothesis; this.action = action;
        this.memoStore = memoStore; this.renderer = renderer; this.p = p;
        this.clock = clock;
    }

    @Transactional
    public Optional<AiFeedback> generateWeekly(Long userId, LocalDate weekEnd) {
        UserAccount user = userRepo.findById(userId).orElse(null);
        if (user == null) return Optional.empty();

        LocalDate weekStart = weekEnd.minusDays(6);
        LocalDate monthStart = weekEnd.withDayOfMonth(1);
        int dim = weekEnd.lengthOfMonth();
        int day = weekEnd.getDayOfMonth();

        // 예산
        long available = user.availableBudget();
        Map<Integer, Long> catBudget = loadCategoryBudgets(userId, monthStart);
        if (catBudget.isEmpty()) return Optional.empty(); // 예산 없으면 진단 불가(콜드스타트는 별도 처리)

        // 기준선 윈도우(직전 baselineWeeks+1 주) 변동 지출
        LocalDateTime from = weekStart.minusWeeks(p.getBaselineWeeks()).atStartOfDay();
        LocalDateTime to = weekEnd.atTime(LocalTime.MAX);
        List<Expense> window = expenseRepo.findByUserIdAndExpenseDateBetween(userId, from, to);

        // ── 메모 임베딩 인덱싱 (미인덱싱 건만 적재, 멱등) ──
        indexMemos(window);

        // ── [6] 예측 + 저축 영향 (1순위) ──
        long totalVarMtd = sum(window, e -> inMonth(e, monthStart, weekEnd) && e.isVariable());
        long totalFixPlanMtd = sum(window, e -> inMonth(e, monthStart, weekEnd) && e.isExpense() && !e.isVariable());
        long remainingFixPlan = estimateRemainingFixedPlanned(window, monthStart, weekEnd);
        double priorRate = priorVariableDailyRate(window, monthStart);
        var proj = projector.project(totalVarMtd, totalFixPlanMtd, remainingFixPlan, day, dim, priorRate);
        long savingsImpact = projector.savingsImpact(proj.predictedMonthEndWon(), available);

        // 카테고리별 초과 예상(선형 근사: 변동 MTD를 월말까지 외삽 + 카테고리 고정·예정 MTD)
        Map<Integer, Long> overspend = new HashMap<>();
        for (var entry : catBudget.entrySet()) {
            int cat = entry.getKey();
            long varMtd = sum(window, e -> e.getCategoryId() == cat && inMonth(e, monthStart, weekEnd) && e.isVariable());
            long fixMtd = sum(window, e -> e.getCategoryId() == cat && inMonth(e, monthStart, weekEnd) && e.isExpense() && !e.isVariable());
            long predictedCat = Math.round((double) varMtd / Math.max(day, 1) * dim) + fixMtd;
            long over = predictedCat - entry.getValue();
            if (over > 0) overspend.put(cat, over);
        }

        // ── [4] 이상치 탐지 (보조) ──
        Anomaly top = detectTopAnomaly(window, weekStart, weekEnd, catBudget);

        boolean triggered = savingsImpact > 0 || top != null || !overspend.isEmpty();

        // ── [5] 원인 (이상치 있을 때) ──
        HypothesisService.Hypothesis hyp = null;
        if (top != null) {
            hyp = hypothesis.infer(top.rep(), top.magnitude(), Categories.name(top.categoryId()),
                    top.rep().getDate());
        }

        // ── [7] 액션 ──
        Optional<ActionService.ActionPlan> plan = action.recommend(userId, overspend, savingsImpact);

        // ── 렌더 + 저장 ──
        Integer topOverCat = overspend.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        boolean anomalyShown = top != null && hyp != null && hyp.confidence() == Confidence.HIGH;
        Confidence overall = anomalyShown ? hyp.confidence() : Confidence.HIGH;

        FeedbackContext ctx = new FeedbackContext(
                proj.predictedMonthEndWon(), available, savingsImpact,
                topOverCat == null ? null : Categories.name(topOverCat),
                topOverCat == null ? 0 : overspend.get(topOverCat),
                top != null, top == null ? null : Categories.name(top.categoryId()),
                top == null ? 0 : top.magnitude(),
                hyp == null ? List.of() : hyp.precedentMemos(),
                hyp == null ? Confidence.LOW : hyp.confidence(),
                plan.isPresent(), plan.map(a -> Categories.name(a.categoryId())).orElse(null),
                plan.map(ActionService.ActionPlan::amountWon).orElse(0L),
                plan.map(ActionService.ActionPlan::varyMessage).orElse(false),
                overall);

        FeedbackRenderer.Rendered out = renderer.render(ctx);

        AiFeedback fb = AiFeedback.builder()
                .userId(userId)
                .yearMonth(monthStart)          // NOT NULL — 분석 주가 속한 달의 1일
                .periodType("WEEKLY")
                .periodStart(weekStart)
                .periodEnd(weekEnd)
                .feedbackText(out.text())
                .confidence(out.confidence().name())
                .categoryId(plan.map(ActionService.ActionPlan::categoryId).orElse(null))
                .adviceType(plan.map(a -> Categories.name(a.categoryId()) + " 절감").orElse(null))
                .createdAt(LocalDateTime.now(clock))
                .build();
        return Optional.of(feedbackRepo.save(fb));
    }

    // ── 헬퍼 ──

    private Map<Integer, Long> loadCategoryBudgets(Long userId, LocalDate monthStart) {
        return budgetRepo.findByUserIdAndYearMonth(userId, monthStart)
                .map(b -> {
                    Map<Integer, Long> m = new HashMap<>();
                    for (BudgetCategory bc : budgetCatRepo.findByBudgetId(b.getId()))
                        m.put(bc.getCategoryId(), bc.getAmount());
                    return m;
                }).orElse(Map.of());
    }

    /** 마지막 7일 변동 거래의 단발 이상치 + 고빈도 카테고리 주간 추세 중, 강도 최대 1건. */
    private Anomaly detectTopAnomaly(List<Expense> window, LocalDate weekStart, LocalDate weekEnd,
                                     Map<Integer, Long> catBudget) {
        // 유효 주 수가 minWeeks 미만 → 콜드스타트 구간, 이상치 탐지 스킵
        // TODO: 콜드스타트 시 설문/온보딩 데이터 기반 피드백으로 대체 (별도 기능 연결 예정)
        long windowDays = ChronoUnit.DAYS.between(
                window.stream().filter(Expense::isVariable).map(Expense::getDate).min(Comparator.naturalOrder()).orElse(weekEnd),
                weekEnd);
        if (windowDays / 7 < p.getMinWeeks()) return null;

        Anomaly best = null;

        // 카테고리별 변동 거래를 날짜순으로 사전 그룹핑 (1회 순회, O(W))
        Map<Integer, List<Expense>> byCat = new HashMap<>();
        for (Expense e : window) {
            if (!e.isVariable()) continue;
            byCat.computeIfAbsent(e.getCategoryId(), k -> new ArrayList<>()).add(e);
        }
        byCat.values().forEach(list -> list.sort(Comparator.comparing(Expense::getDate)));

        // 단발: 이번 주 변동 거래에 대해 같은 카테고리 리스트에서만 히스토리 추출
        for (var entry : byCat.entrySet()) {
            int cat = entry.getKey();
            List<Expense> catExpenses = entry.getValue();
            long budget = catBudget.getOrDefault(cat, 0L);

            for (Expense e : catExpenses) {
                LocalDate d = e.getDate();
                if (d.isBefore(weekStart) || d.isAfter(weekEnd)) continue;

                double[] hist = catExpenses.stream()
                        .filter(x -> x.getDate().isBefore(d)
                                && ChronoUnit.DAYS.between(x.getDate(), d) <= p.getBaselineWeeks() * 7L)
                        .mapToDouble(Expense::getAmount).toArray();
                var z = detector.checkPoint(e.getAmount(), hist, budget);
                if (z.isPresent()) {
                    double mag = hist.length > 0 ? e.getAmount() / Math.max(RobustStats.median(hist), 1) : z.getAsDouble();
                    if (best == null || mag > best.magnitude())
                        best = new Anomaly(e.getCategoryId(), e, mag);
                }
            }
        }

        // 추세: 고빈도 카테고리 — 사전 그룹핑된 리스트 재활용
        int weeks = p.getBaselineWeeks();
        for (int cat : catBudget.keySet().stream().filter(detector::isHighFreq).toList()) {
            double[] weekly = weeklySeries(window, cat, weekEnd, weeks);
            if (detector.trendConfirmed(weekly, weeks - 1, catBudget.getOrDefault(cat, 0L))) {
                double mag = 1.5;
                List<Expense> catExpenses = byCat.getOrDefault(cat, List.of());
                Expense rep = catExpenses.stream()
                        .filter(x -> !x.getDate().isBefore(weekStart) && !x.getDate().isAfter(weekEnd))
                        .max(Comparator.comparing(Expense::getExpenseDate)).orElse(null);
                if (rep != null && (best == null || mag > best.magnitude()))
                    best = new Anomaly(cat, rep, mag);
            }
        }
        return best;
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

    private long estimateRemainingFixedPlanned(List<Expense> window, LocalDate monthStart, LocalDate weekEnd) {
        // 남은 예정(RECORD-04): 이번 달 미래 일자 is_planned
        long plannedFuture = window.stream()
                .filter(e -> e.isPlanned() && !e.getDate().isBefore(monthStart) && e.getDate().isAfter(weekEnd))
                .mapToLong(Expense::getAmount).sum();
        // 남은 반복 추정: 전월 반복 항목 중 결제일(day) > 오늘 인 것의 합 (TODO: 실제 반복 스케줄 테이블이 있으면 그걸 사용)
        LocalDate prevMonth = monthStart.minusMonths(1);
        long recurringRemaining = window.stream()
                .filter(e -> e.isRecurring() && !e.getDate().isBefore(prevMonth) && e.getDate().isBefore(monthStart))
                .filter(e -> e.getDate().getDayOfMonth() > weekEnd.getDayOfMonth())
                .mapToLong(Expense::getAmount).sum();
        return plannedFuture + recurringRemaining;
    }

    private double priorVariableDailyRate(List<Expense> window, LocalDate monthStart) {
        LocalDate prevMonth = monthStart.minusMonths(1);
        long sum = window.stream()
                .filter(e -> e.isVariable() && !e.getDate().isBefore(prevMonth) && e.getDate().isBefore(monthStart))
                .mapToLong(Expense::getAmount).sum();
        int days = prevMonth.lengthOfMonth();
        return sum > 0 ? (double) sum / days : 0.0;
    }

    private interface Pred { boolean test(Expense e); }
    private long sum(List<Expense> xs, Pred f) {
        long s = 0; for (Expense e : xs) if (f.test(e)) s += e.getAmount(); return s;
    }
    private boolean inMonth(Expense e, LocalDate monthStart, LocalDate weekEnd) {
        LocalDate d = e.getDate();
        return !d.isBefore(monthStart) && !d.isAfter(weekEnd);
    }

    /** 윈도우 내 메모 있는 거래를 벡터 스토어에 인덱싱. index()는 이미 적재된 건은 스킵(멱등). */
    private void indexMemos(List<Expense> window) {
        for (Expense e : window) {
            if (e.getMemo() != null && !e.getMemo().isBlank()) {
                memoStore.index(e, Categories.name(e.getCategoryId()));
            }
        }
    }

    private record Anomaly(int categoryId, Expense rep, double magnitude) {}
}
