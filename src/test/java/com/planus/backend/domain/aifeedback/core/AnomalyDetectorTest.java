package com.planus.backend.domain.aifeedback.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AnomalyDetector 단위 테스트 — 스프링 컨텍스트 없이 new 로 생성.
 * 임계값은 AiFeedbackProperties 기본값을 객체에서 직접 읽어 사용하므로,
 * 팀이 임계값을 조정해도 테스트는 그에 맞춰 동작한다(특정 숫자에 의존하지 않음).
 */
class AnomalyDetectorTest {

    private final AiFeedbackProperties p = new AiFeedbackProperties();
    private final AnomalyDetector detector = new AnomalyDetector(p);

    // ---------- 단발 이상치 (checkPoint) ----------

    @Test
    void checkPoint_flags_obvious_outlier() {
        long budget = 1_000_000;
        double absThr = Math.max(budget * p.getPointFrac(), p.getPointMin());
        double[] history = stableHistory(absThr / 10.0); // 평소 소액(약간의 분산)
        double amount = absThr + 1_000_000; // 금액 임계 위 + 평소보다 훨씬 큼(z 큼)
        assertThat(detector.checkPoint(amount, history, budget)).isPresent();
    }

    @Test
    void checkPoint_ignores_statistical_spike_below_amount_threshold() {
        // 통계적으론 극단(z 매우 큼)이지만 금액이 예산 대비 임계 미만이면 무시 — 금액 게이트 검증
        long budget = 1_000_000;
        double absThr = Math.max(budget * p.getPointFrac(), p.getPointMin());
        double[] history = stableHistory(absThr / 10.0);
        double amount = absThr - 1; // 임계 바로 아래
        assertThat(detector.checkPoint(amount, history, budget)).isEmpty();
    }

    @Test
    void checkPoint_with_sparse_history_flags_only_large_single() {
        long budget = 1_000_000;
        double sparseThr = Math.max(budget * p.getSparseFrac(), p.getSparseMin());
        double[] none = new double[0]; // 과거 거의 없음 → 큰 단건만
        assertThat(detector.checkPoint(sparseThr + 1_000_000, none, budget)).isPresent();
        assertThat(detector.checkPoint(sparseThr - 1, none, budget)).isEmpty();
    }

    // ---------- 추세 (trendConfirmed) ----------

    @Test
    void trendConfirmed_true_for_sustained_two_week_rise() {
        long budget = 100_000;
        double[] weekly = {10_000, 10_000, 10_000, 10_000, 10_000, 200_000, 200_000, 200_000};
        assertThat(detector.trendConfirmed(weekly, 7, budget)).isTrue();
    }

    @Test
    void trendConfirmed_false_for_flat_spend() {
        long budget = 100_000;
        double[] weekly = {10_000, 10_000, 10_000, 10_000, 10_000, 10_000, 10_000, 10_000};
        assertThat(detector.trendConfirmed(weekly, 7, budget)).isFalse();
    }

    @Test
    void trendConfirmed_false_for_single_week_spike() {
        // 마지막 주만 튐 → 2주 연속 조건 미충족 → 추세로 인정하지 않음
        long budget = 100_000;
        double[] weekly = {10_000, 10_000, 10_000, 10_000, 10_000, 10_000, 10_000, 200_000};
        assertThat(detector.trendConfirmed(weekly, 7, budget)).isFalse();
    }

    // ---------- 고빈도 분류 ----------

    @Test
    void isHighFreq_matches_configured_categories() {
        int[] hf = p.getHighFreqCategories();
        assertThat(detector.isHighFreq(hf[0])).isTrue(); // 설정된 고빈도 카테고리
        assertThat(detector.isHighFreq(999)).isFalse(); // 존재하지 않는 카테고리
    }

    // ---------- 스파이크성 카테고리 ----------

    @Test
    void isSpikeProne_matchesConfigured() {
        List<Long> spikeList = p.getCategory().getSpikeProne();
        int configured = spikeList.get(0).intValue();
        // max() + 1은 목록 전체에 없는 값이 보장됨 — 설정이 바뀌어도 안전
        int notConfigured = spikeList.stream().mapToInt(Long::intValue).max().orElse(0) + 1;
        assertThat(detector.isSpikeProne(configured)).isTrue();
        assertThat(detector.isSpikeProne(notConfigured)).isFalse();
    }

    /** 중앙값≈base, MAD가 median*0.10 을 넘도록 분산을 둔 소액 이력(robust-z 분기 진입). */
    private static double[] stableHistory(double base) {
        return new double[] {
            base * 0.8, base * 1.2, base * 0.9, base * 1.3,
            base * 1.0, base * 1.1, base * 0.7, base * 1.4
        };
    }
}
