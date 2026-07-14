package com.planus.backend.domain.aifeedback.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.planus.backend.domain.aifeedback.core.PacingComparator.PacingDisplay;
import com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult;
import com.planus.backend.domain.aifeedback.core.PacingComparator.PastMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PacingComparatorTest {

    private final PacingComparator comparator = new PacingComparator();

    @Test
    @DisplayName("과거 대비 빠른 소진이면 pacingRatio > 1.0")
    void compare_fasterPacing() {
        List<PastMonth> past = List.of(
                new PastMonth(220_000, 7, 1_000_000),
                new PastMonth(240_000, 7, 1_000_000),
                new PastMonth(200_000, 7, 1_000_000));

        PacingResult result = comparator.compare(350_000, 1_000_000, 7, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.currentRate()).isCloseTo(0.35, within(1e-6));
        assertThat(result.pacingRatio()).isGreaterThan(1.0);
    }

    @Test
    @DisplayName("과거 대비 느린 소진이면 pacingRatio < 1.0")
    void compare_slowerPacing() {
        List<PastMonth> past = List.of(
                new PastMonth(300_000, 10, 1_000_000),
                new PastMonth(280_000, 10, 1_000_000),
                new PastMonth(320_000, 10, 1_000_000));

        PacingResult result = comparator.compare(200_000, 1_000_000, 10, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.pacingRatio()).isLessThan(1.0);
    }

    @Test
    @DisplayName("과거 데이터 없으면 null 반환")
    void compare_noPastData_returnsNull() {
        assertThat(comparator.compare(100_000, 1_000_000, 7, 30, List.of())).isNull();
        assertThat(comparator.compare(100_000, 1_000_000, 7, 30, null)).isNull();
    }

    @Test
    @DisplayName("월초(경과일/총일 < 0.4)이면 WIDE 밴드")
    void compare_earlyMonth_wideBand() {
        List<PastMonth> past = List.of(
                new PastMonth(50_000, 3, 1_000_000),
                new PastMonth(60_000, 3, 1_000_000),
                new PastMonth(55_000, 3, 1_000_000));

        PacingResult result = comparator.compare(70_000, 1_000_000, 3, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.bandWidth()).isEqualTo(BandWidth.WIDE);
    }

    @Test
    @DisplayName("중간 시점(0.4 ≤ progress < 0.6) + 2개월이면 MEDIUM 밴드")
    void compare_midMonth_twoMonths_mediumBand() {
        List<PastMonth> past =
                List.of(new PastMonth(400_000, 15, 1_000_000), new PastMonth(450_000, 15, 1_000_000));

        PacingResult result = comparator.compare(500_000, 1_000_000, 15, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.bandWidth()).isEqualTo(BandWidth.MEDIUM);
    }

    @Test
    @DisplayName("월말(progress ≥ 0.6) + 3개월+이면 NARROW 밴드")
    void compare_lateMonth_threeMonths_narrowBand() {
        List<PastMonth> past = List.of(
                new PastMonth(700_000, 25, 1_000_000),
                new PastMonth(750_000, 25, 1_000_000),
                new PastMonth(720_000, 25, 1_000_000));

        PacingResult result = comparator.compare(800_000, 1_000_000, 25, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.bandWidth()).isEqualTo(BandWidth.NARROW);
    }

    @Test
    @DisplayName("과거 1개월만 있으면 WIDE 밴드 + SINGLE displayType")
    void compare_singlePastMonth_wideBandAndSingle() {
        List<PastMonth> past = List.of(new PastMonth(220_000, 15, 1_000_000));

        PacingResult result = comparator.compare(250_000, 1_000_000, 15, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.bandWidth()).isEqualTo(BandWidth.WIDE);
        assertThat(result.displayType()).isEqualTo(PacingDisplay.SINGLE);
        assertThat(result.monthsAvailable()).isEqualTo(1);
        assertThat(result.referenceMin()).isNull();
        assertThat(result.referenceMax()).isNull();
    }

    @Test
    @DisplayName("과거 2개월이면 RANGE displayType + referenceMin/Max 제공")
    void compare_twoMonths_rangeDisplayType() {
        List<PastMonth> past =
                List.of(new PastMonth(200_000, 10, 1_000_000), new PastMonth(300_000, 10, 1_000_000));

        PacingResult result = comparator.compare(250_000, 1_000_000, 10, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.displayType()).isEqualTo(PacingDisplay.RANGE);
        assertThat(result.monthsAvailable()).isEqualTo(2);
        assertThat(result.referenceMin()).isCloseTo(0.2, within(1e-6));
        assertThat(result.referenceMax()).isCloseTo(0.3, within(1e-6));
    }

    @Test
    @DisplayName("과거 3개월+이면 MEDIAN displayType")
    void compare_threeMonths_medianDisplayType() {
        List<PastMonth> past = List.of(
                new PastMonth(200_000, 10, 1_000_000),
                new PastMonth(300_000, 10, 1_000_000),
                new PastMonth(250_000, 10, 1_000_000));

        PacingResult result = comparator.compare(250_000, 1_000_000, 10, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.displayType()).isEqualTo(PacingDisplay.MEDIAN);
        assertThat(result.monthsAvailable()).isEqualTo(3);
        assertThat(result.referenceMin()).isNull();
        assertThat(result.referenceMax()).isNull();
    }

    @Test
    @DisplayName("과거와 경과일이 다르면 비율 보정 적용")
    void compare_differentElapsedDays_appliesRateCorrection() {
        List<PastMonth> past = List.of(
                new PastMonth(500_000, 5, 1_000_000),
                new PastMonth(500_000, 5, 1_000_000),
                new PastMonth(500_000, 5, 1_000_000));

        PacingResult result = comparator.compare(600_000, 1_000_000, 10, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.historicalAvgRate()).isCloseTo(1.0, within(1e-6));
        assertThat(result.pacingRatio()).isCloseTo(0.6, within(1e-6));
    }

    @Test
    @DisplayName("월말(progress ≥ 0.4) + 2개월이면 MEDIUM 밴드 (3개월 미만)")
    void compare_lateMonth_twoMonths_stillMedium() {
        List<PastMonth> past =
                List.of(new PastMonth(700_000, 25, 1_000_000), new PastMonth(750_000, 25, 1_000_000));

        PacingResult result = comparator.compare(800_000, 1_000_000, 25, 30, past);

        assertThat(result).isNotNull();
        // progress=0.83 ≥ 0.6 but pastMonthCount=2 < 3 → MEDIUM
        assertThat(result.bandWidth()).isEqualTo(BandWidth.MEDIUM);
    }
}
