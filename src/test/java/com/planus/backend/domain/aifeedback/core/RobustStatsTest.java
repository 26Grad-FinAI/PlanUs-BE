package com.planus.backend.domain.aifeedback.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** RobustStats: 중앙값·MAD·modified z 정확성 + 이상치 견고성. */
class RobustStatsTest {

    @Test
    void median_holds_for_odd_even_and_unsorted() {
        assertThat(RobustStats.median(new double[]{1, 2, 3, 4, 5})).isEqualTo(3.0);
        assertThat(RobustStats.median(new double[]{1, 2, 3, 4})).isEqualTo(2.5);
        assertThat(RobustStats.median(new double[]{5, 1, 3, 2, 4})).isEqualTo(3.0); // 정렬 안 돼 있어도
    }

    @Test
    void median_is_not_moved_by_an_outlier() {
        // 1000이라는 극단값이 있어도 중앙값은 3 (평균이라면 202로 끌려감)
        assertThat(RobustStats.median(new double[]{1, 2, 3, 4, 1000})).isEqualTo(3.0);
    }

    @Test
    void mad_basic_and_robust_to_outlier() {
        assertThat(RobustStats.mad(new double[]{1, 2, 3, 4, 5})).isEqualTo(1.0);
        // 1000이 있어도 MAD는 1 (표준편차라면 폭증)
        assertThat(RobustStats.mad(new double[]{1, 2, 3, 4, 1000})).isEqualTo(1.0);
    }

    @Test
    void modifiedZ_formula_and_sign() {
        assertThat(RobustStats.modifiedZ(10, 3, 1)).isCloseTo(4.7215, within(1e-4)); // 0.6745 * 7
        assertThat(RobustStats.modifiedZ(1, 3, 1)).isNegative();                      // 중앙값보다 작음
        assertThat(RobustStats.modifiedZ(5, 3, 0)).isEqualTo(0.0);                    // MAD=0 가드
    }

    @Test
    void mean_basic() {
        assertThat(RobustStats.mean(new double[]{2, 4, 6})).isEqualTo(4.0);
    }
}
