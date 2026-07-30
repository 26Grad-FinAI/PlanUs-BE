package com.planus.backend.domain.aifeedback.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** BudgetProjector: 월말 예측·저축 영향·진척률 계산. */
class BudgetProjectorTest {

    private final BudgetProjector projector = new BudgetProjector();

    @Test
    void project_blends_current_and_prior_daily_rate() {
        // 20일 중 10일차, 변동 MTD 30만(일평균 3만), 과거 일평균 2만, w=0.5 → 혼합 2.5만/일
        // 남은 변동 = 2.5만 × 10일 = 25만, 예상 = (30만+10만 고정MTD) + 15만 잔여고정 + 25만 = 80만
        var p = projector.project(300_000, 100_000, 150_000, 10, 20, 20_000, null, null);
        assertThat(p.predictedMonthEndWon()).isEqualTo(800_000);
        assertThat(p.remainingVariableWon()).isEqualTo(250_000);
        assertThat(p.dailyRateWon()).isCloseTo(25_000.0, within(1e-6));
    }

    @Test
    void project_uses_current_rate_when_no_prior_data() {
        // 과거 일평균 0 → 이번 달 일평균(2만)만 사용. 남은 변동 = 2만 × 20일 = 40만
        var p = projector.project(200_000, 0, 0, 10, 30, 0.0, null, null);
        assertThat(p.predictedMonthEndWon()).isEqualTo(600_000);
        assertThat(p.remainingVariableWon()).isEqualTo(400_000);
        assertThat(p.dailyRateWon()).isCloseTo(20_000.0, within(1e-6));
    }

    @Test
    void savingsImpact_negative_when_overspend_positive_when_surplus() {
        // 가용예산(600k) - 예상지출(800k) = -200k → 초과 위험
        assertThat(projector.savingsImpact(800_000, 600_000)).isEqualTo(-200_000);
        // 가용예산(600k) - 예상지출(500k) = +100k → 여유
        assertThat(projector.savingsImpact(500_000, 600_000)).isEqualTo(100_000);
    }

    @Test
    void burnRate_ratio_and_zero_budget_guard() {
        assertThat(projector.burnRate(120_000, 100_000)).isCloseTo(1.2, within(1e-9));
        assertThat(projector.burnRate(50_000, 0)).isEqualTo(Double.POSITIVE_INFINITY); // 예산 0 + 지출 있음 → 초과 확정
        assertThat(projector.burnRate(0, 0)).isEqualTo(0.0); // 예산 0 + 지출 없음 → 0
    }

    // ── 요일가중 예측 테스트 ──

    @Test
    void project_withDayOfWeekWeights_adjustsPrediction() {
        // 2026-07-01 = 수요일. 30일 중 11일차 완료, 남은 19일(12~30일).
        // 남은 19일: 12(일) 13(월) 14(화) 15(수) 16(목) 17(금) 18(토) 19(일)
        //           20(월) 21(화) 22(수) 23(목) 24(금) 25(토) 26(일) 27(월) 28(화) 29(수) 30(목)
        // 주말(토·일): 12,18,19,25,26 = 5일, 평일: 14일
        LocalDate monthStart = LocalDate.of(2026, 7, 1);
        // 가중치: 월~금=0.70, 토~일=1.75 → 합 = 0.70*5 + 1.75*2 = 7.0 ✓
        double[] weights = {0.70, 0.70, 0.70, 0.70, 0.70, 1.75, 1.75};

        // blendedRate = 25,000 (same as blend test)
        var p = projector.project(300_000, 100_000, 150_000, 11, 31, 20_000, weights, monthStart);

        // 남은 20일(12~31일):
        // 12(일)1.75 13(월)0.70 14(화)0.70 15(수)0.70 16(목)0.70 17(금)0.70 18(토)1.75
        // 19(일)1.75 20(월)0.70 21(화)0.70 22(수)0.70 23(목)0.70 24(금)0.70 25(토)1.75
        // 26(일)1.75 27(월)0.70 28(화)0.70 29(수)0.70 30(목)0.70 31(금)0.70
        // 주말(토·일): 12,18,19,25,26 = 5일 × 1.75 = 8.75
        // 평일(월~금): 15일 × 0.70 = 10.50
        // weightedDays = 19.25

        // w = 11/31 ≈ 0.3548, currentRate = 300000/11 ≈ 27272.73
        // blendedRate = 0.3548 * 27272.73 + 0.6452 * 20000 ≈ 9677.42 + 12903.23 ≈ 22580.65
        double expectedBlended = (11.0 / 31) * (300_000.0 / 11) + (1 - 11.0 / 31) * 20_000;
        double expectedWeightedDays = 5 * 1.75 + 15 * 0.70;
        long expectedRemainingVar = Math.round(expectedBlended * expectedWeightedDays);

        assertThat(p.remainingVariableWon()).isEqualTo(expectedRemainingVar);
        assertThat(p.dailyRateWon()).isCloseTo(expectedBlended, within(1e-6));
    }

    @Test
    void project_nullWeights_fallsBackToUniform() {
        // null weights → 기존 균등 동작과 동일해야 함
        var withNull = projector.project(300_000, 100_000, 150_000, 10, 20, 20_000, null, null);
        var withNullStart =
                projector.project(300_000, 100_000, 150_000, 10, 20, 20_000, new double[] {1, 1, 1, 1, 1, 1, 1}, null);

        assertThat(withNull.predictedMonthEndWon()).isEqualTo(800_000);
        // monthStart가 null이면 weights가 있어도 균등 처리
        assertThat(withNullStart.predictedMonthEndWon()).isEqualTo(800_000);
    }

    @Test
    void project_uniformWeights_sameAsWithout() {
        // 모든 가중치 1.0 → 균등과 동일 결과
        LocalDate monthStart = LocalDate.of(2026, 7, 1);
        double[] uniform = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};

        var withWeights = projector.project(300_000, 100_000, 150_000, 10, 31, 20_000, uniform, monthStart);
        var without = projector.project(300_000, 100_000, 150_000, 10, 31, 20_000, null, null);

        assertThat(withWeights.predictedMonthEndWon()).isEqualTo(without.predictedMonthEndWon());
        assertThat(withWeights.remainingVariableWon()).isEqualTo(without.remainingVariableWon());
    }
}
