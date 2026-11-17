package com.planus.backend.domain.aifeedback.core;

import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import java.util.Arrays;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

/**
 * [4] 이상치 / 변화 탐지.
 *  - 단발 금액 이상치: 모든 카테고리, robust modified-z + 절대금액(+ 과거부족/저분산 대체).
 *  - 주간 추세: 고빈도 카테고리만, 2-윈도우(최근2주 vs 직전4주, 비중첩) + 2주 연속.
 * 합성 실험으로 검증·확정된 규칙. 신뢰도 게이팅은 ConfidenceEvaluator에서 별도.
 */
@Component
public class AnomalyDetector {

    private final AiFeedbackProperties p;

    /** @param p 이상치 탐지 임계값 등 설정 프로퍼티 */
    public AnomalyDetector(AiFeedbackProperties p) {
        this.p = p;
    }

    /**
     * 단발 이상치 판정. 양성이면 modified-z(또는 대체 점수)를, 아니면 비어있음.
     * @param amount       거래 금액(원)
     * @param history      같은 사용자·카테고리의 직전 8주 거래 금액들(현재 거래 제외)
     * @param categoryBudgetWon 그 달 카테고리 예산(원)
     */
    public OptionalDouble checkPoint(double amount, double[] history, long categoryBudgetWon) {
        double absThr = Math.max(categoryBudgetWon * p.getPointFrac(), p.getPointMin());
        if (history.length >= 2) {
            double med = RobustStats.median(history);
            double madVal = RobustStats.mad(history);
            if (madVal > med * 0.10) { // 분산 충분 → robust z
                double z = RobustStats.modifiedZ(amount, med, madVal);
                if (z >= p.getPointZ() && amount >= absThr) return OptionalDouble.of(z);
            } else { // 분산 미미 → 중앙값 N배
                if (amount >= med * p.getLowSpreadMult() && amount >= absThr)
                    return OptionalDouble.of(med > 0 ? amount / med : p.getLowSpreadMult());
            }
        } else { // 과거 거의 없음 → 큰 단건만
            if (amount >= Math.max(categoryBudgetWon * p.getSparseFrac(), p.getSparseMin()))
                return OptionalDouble.of(p.getSparseFrac() * 10); // 대략적 강도
        }
        return OptionalDouble.empty();
    }

    /**
     * 고빈도 카테고리 여부를 반환한다. 주간 추세 탐지 대상 판별에 사용.
     *
     * @param categoryId 카테고리 ID
     * @return 고빈도 카테고리이면 true
     */
    public boolean isHighFreq(int categoryId) {
        for (int c : p.getHighFreqCategories()) if (c == categoryId) return true;
        return false;
    }

    /**
     * 스파이크성 카테고리(의료·경조사 등) 여부를 반환한다.
     * 원래 가끔 크게 나오는 카테고리이므로 단발 이상치 탐지를 스킵해야 한다.
     * 호출 측에서 이 메서드로 필터링한다.
     *
     * @param categoryId 카테고리 ID
     * @return 스파이크성 카테고리이면 true
     */
    public boolean isSpikeProne(int categoryId) {
        for (long c : p.getCategory().getSpikeProne()) {
            if (c == categoryId) return true;
        }
        return false;
    }

    /**
     * 주간 추세(상승) 확정 여부. 고빈도 카테고리에만 호출.
     * 2-윈도우: 최근 2주 평균 ≥ 직전 4주 평균 × ratio AND (최근-직전) ≥ 예산×frac, 2주 연속 충족.
     * @param weekly  주간 합계 시계열(과거→현재 순, 변동 지출만; 고정·예정 제외)
     * @param wIndex  판정 대상 주의 인덱스
     */
    public boolean trendConfirmed(double[] weekly, int wIndex, long categoryBudgetWon) {
        return rising(weekly, wIndex, categoryBudgetWon) && rising(weekly, wIndex - 1, categoryBudgetWon);
    }

    /**
     * 추세 구간 통계. 확정 판정({@link #trendConfirmed})과 같은 구간을 쓴다 (#138):
     * 최근 2주(w-1, w) 주당 평균 vs 그 앞 4주(w-6..w-3, 비중첩) 주당 평균.
     *
     * @param recentMean 최근 2주 주당 평균(원)
     * @param priorMean  그 앞 4주 주당 평균(원)
     * @param ratio      recentMean ÷ priorMean. priorMean이 0이면 추세 확정 기준 배수({@code trendRatio})
     */
    public record TrendStats(double recentMean, double priorMean, double ratio) {}

    /**
     * 주 {@code w} 기준 추세 구간 통계. 확정 여부와 무관하게 계산한다.
     * 신뢰도·프롬프트의 배수가 확정 판정과 다른 구간에서 나오지 않도록 호출부는 이 값을 그대로 쓴다.
     *
     * @return 구간이 모자라면(w &lt; 6) null
     */
    public TrendStats trendStats(double[] weekly, int w) {
        if (w < 6) return null; // 직전 4주(w-6..w-3) + 최근 2주(w-1..w) 확보 필요
        double[] recent = {weekly[w - 1], weekly[w]};
        double[] prior = Arrays.copyOfRange(weekly, w - 6, w - 2); // w-6,w-5,w-4,w-3 (비중첩)
        double rm = RobustStats.mean(recent), pm = RobustStats.mean(prior);
        return new TrendStats(rm, pm, pm > 0 ? rm / pm : p.getTrendRatio());
    }

    private boolean rising(double[] weekly, int w, long budgetWon) {
        TrendStats t = trendStats(weekly, w);
        if (t == null) return false;
        double frac = budgetWon * p.getTrendFrac();
        if (t.priorMean() <= 0) return t.recentMean() > 0 && t.recentMean() >= frac;
        return t.recentMean() >= t.priorMean() * p.getTrendRatio() && (t.recentMean() - t.priorMean()) >= frac;
    }
}
