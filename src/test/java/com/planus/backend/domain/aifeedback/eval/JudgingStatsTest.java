package com.planus.backend.domain.aifeedback.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.planus.backend.domain.aifeedback.eval.JudgingStats.Interval;
import java.util.List;
import org.junit.jupiter.api.Test;

class JudgingStatsTest {

    @Test
    void Wilson_구간은_PR123에_기록한_값과_같다() {
        // PR #123: 실험 0 32/68 → 36–59%, 실험 10 8/69 → 6–21%
        Interval exp0 = JudgingStats.wilson(32, 68);
        Interval exp10 = JudgingStats.wilson(8, 69);

        assertThat(JudgingStats.pct(exp0)).isEqualTo("36–59%");
        assertThat(JudgingStats.pct(exp10)).isEqualTo("6–21%");
    }

    @Test
    void Wilson_구간은_0건이어도_범위_안이다() {
        Interval i = JudgingStats.wilson(0, 20);

        assertThat(i.lo()).isZero();
        assertThat(i.hi()).isBetween(0.10, 0.20);
    }

    @Test
    void Fisher_양측_p값은_PR123에_기록한_값과_같다() {
        // 32/68 vs 8/69 → p = 4.8×10⁻⁶
        double p = JudgingStats.fisherTwoSided(32, 36, 8, 61);

        assertThat(p).isCloseTo(4.8e-6, within(0.1e-6));
    }

    @Test
    void Fisher_차이가_없으면_p는_1() {
        assertThat(JudgingStats.fisherTwoSided(5, 5, 5, 5)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void Fisher_교과서_예시() {
        // 차 맛 감별 (Fisher 1935): [[3,1],[1,3]] 양측 p = 0.4857
        assertThat(JudgingStats.fisherTwoSided(3, 1, 1, 3)).isCloseTo(0.4857, within(1e-4));
    }

    @Test
    void 카파는_완전_일치면_1_우연_수준이면_0() {
        List<Boolean> a = List.of(true, true, false, false);

        assertThat(JudgingStats.cohenKappa(a, a)).isEqualTo(1.0);
        assertThat(JudgingStats.cohenKappa(a, List.of(true, false, true, false)))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void 카파_예시값() {
        // 20항목: 둘 다 틀림 5, 둘 다 맞음 10, 1만 틀림 3, 2만 틀림 2 → po=0.75, pe=0.4×0.35+0.6×0.65=0.53, κ≈0.468
        List<Boolean> x = new java.util.ArrayList<>(), y = new java.util.ArrayList<>();
        add(x, y, true, true, 5);
        add(x, y, false, false, 10);
        add(x, y, true, false, 3);
        add(x, y, false, true, 2);

        assertThat(JudgingStats.cohenKappa(x, y)).isCloseTo(0.4681, within(1e-3));
    }

    @Test
    void 카파는_판정이_한쪽으로_몰리면_계산_불가() {
        assertThat(JudgingStats.cohenKappa(List.of(false, false), List.of(false, false)))
                .isNaN();
    }

    @Test
    void 군집_부트스트랩은_같은_시드면_같은_구간이고_비율을_감싼다() {
        List<int[]> clusters = List.of(new int[] {0, 3}, new int[] {1, 3}, new int[] {3, 3}, new int[] {0, 3});

        Interval a = JudgingStats.clusterBootstrap(clusters, 2000, 7);
        Interval b = JudgingStats.clusterBootstrap(clusters, 2000, 7);

        assertThat(a).isEqualTo(b);
        assertThat(a.lo()).isLessThanOrEqualTo(4.0 / 12);
        assertThat(a.hi()).isGreaterThanOrEqualTo(4.0 / 12);
    }

    private static void add(List<Boolean> x, List<Boolean> y, boolean a, boolean b, int times) {
        for (int i = 0; i < times; i++) {
            x.add(a);
            y.add(b);
        }
    }
}
