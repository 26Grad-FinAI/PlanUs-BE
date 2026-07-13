package com.planus.backend.domain.aifeedback.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
        // 과거: 7일차에 예산의 22% 사용, 현재: 35% 사용
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
    @DisplayName("월말(경과일/총일 >= 0.4, 과거 2개월+)이면 NARROW 밴드")
    void compare_lateMonth_narrowBand() {
        List<PastMonth> past = List.of(new PastMonth(700_000, 25, 1_000_000), new PastMonth(750_000, 25, 1_000_000));

        PacingResult result = comparator.compare(800_000, 1_000_000, 25, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.bandWidth()).isEqualTo(BandWidth.NARROW);
    }

    @Test
    @DisplayName("과거 1개월만 있으면 WIDE 밴드")
    void compare_singlePastMonth_wideBand() {
        List<PastMonth> past = List.of(new PastMonth(220_000, 15, 1_000_000));

        PacingResult result = comparator.compare(250_000, 1_000_000, 15, 30, past);

        assertThat(result).isNotNull();
        assertThat(result.bandWidth()).isEqualTo(BandWidth.WIDE);
    }
}
