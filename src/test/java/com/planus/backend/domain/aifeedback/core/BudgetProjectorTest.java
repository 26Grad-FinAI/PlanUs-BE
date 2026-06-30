package com.planus.backend.domain.aifeedback.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** BudgetProjector: 월말 예측·저축 영향·진척률 계산. */
class BudgetProjectorTest {

    private final BudgetProjector projector = new BudgetProjector();

    @Test
    void project_blends_current_and_prior_daily_rate() {
        // 20일 중 10일차, 변동 MTD 30만(일평균 3만), 과거 일평균 2만, w=0.5 → 혼합 2.5만/일
        // 남은 변동 = 2.5만 × 10일 = 25만, 예상 = (30만+10만 고정MTD) + 15만 잔여고정 + 25만 = 80만
        var p = projector.project(300_000, 100_000, 150_000, 10, 20, 20_000);
        assertThat(p.predictedMonthEndWon()).isEqualTo(800_000);
        assertThat(p.remainingVariableWon()).isEqualTo(250_000);
        assertThat(p.dailyRateWon()).isCloseTo(25_000.0, within(1e-6));
    }

    @Test
    void project_uses_current_rate_when_no_prior_data() {
        // 과거 일평균 0 → 이번 달 일평균(2만)만 사용. 남은 변동 = 2만 × 20일 = 40만
        var p = projector.project(200_000, 0, 0, 10, 30, 0.0);
        assertThat(p.predictedMonthEndWon()).isEqualTo(600_000);
        assertThat(p.remainingVariableWon()).isEqualTo(400_000);
        assertThat(p.dailyRateWon()).isCloseTo(20_000.0, within(1e-6));
    }

    @Test
    void savingsImpact_positive_when_over_negative_when_under() {
        assertThat(projector.savingsImpact(800_000, 600_000)).isEqualTo(200_000);   // 가용예산 초과
        assertThat(projector.savingsImpact(500_000, 600_000)).isEqualTo(-100_000);  // 여유(더 저축)
    }

    @Test
    void burnRate_ratio_and_zero_budget_guard() {
        assertThat(projector.burnRate(120_000, 100_000)).isCloseTo(1.2, within(1e-9));
        assertThat(projector.burnRate(50_000, 0)).isEqualTo(Double.POSITIVE_INFINITY); // 예산 0 + 지출 있음 → 초과 확정
        assertThat(projector.burnRate(0, 0)).isEqualTo(0.0); // 예산 0 + 지출 없음 → 0
    }
}