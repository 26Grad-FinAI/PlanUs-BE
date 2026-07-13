package com.planus.backend.domain.aifeedback.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActivityGuardTest {

    private final AiFeedbackProperties p = new AiFeedbackProperties();
    private final ActivityGuard guard = new ActivityGuard(p);

    @Test
    @DisplayName("이번 주 건수가 평소의 50% 미만이면 LOW_DATA")
    void isLowData_belowThreshold() {
        assertThat(guard.isLowData(4, 10.0)).isTrue(); // 4 < 10 * 0.5
    }

    @Test
    @DisplayName("이번 주 건수가 평소의 50% 이상이면 통과")
    void isLowData_atOrAboveThreshold() {
        assertThat(guard.isLowData(5, 10.0)).isFalse(); // 5 >= 10 * 0.5
        assertThat(guard.isLowData(10, 10.0)).isFalse();
    }

    @Test
    @DisplayName("normalWeeklyCount는 과거 주별 건수의 중앙값을 반환한다")
    void normalWeeklyCount_returnsMedian() {
        List<Long> counts = List.of(5L, 8L, 12L, 7L, 10L);
        double normal = guard.normalWeeklyCount(counts);
        assertThat(normal).isEqualTo(8.0); // 정렬: 5,7,8,10,12 → 중앙값 8
    }

    @Test
    @DisplayName("관측 3주 미만이면 가드 비활성 (MAX_VALUE 반환)")
    void normalWeeklyCount_lessThanThreeWeeks_returnsMaxValue() {
        assertThat(guard.normalWeeklyCount(List.of(5L, 8L))).isEqualTo(Double.MAX_VALUE);
        assertThat(guard.normalWeeklyCount(List.of())).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("가드 비활성 시 isLowData는 항상 false")
    void isLowData_withMaxValue_alwaysPasses() {
        // normalWeeklyCount가 MAX_VALUE이면 어떤 thisWeekCount도 통과
        assertThat(guard.isLowData(0, Double.MAX_VALUE)).isFalse();
    }
}
