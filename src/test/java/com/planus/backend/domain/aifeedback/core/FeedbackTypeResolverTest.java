package com.planus.backend.domain.aifeedback.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeedbackTypeResolverTest {

    private final FeedbackTypeResolver resolver = new FeedbackTypeResolver();

    @Test
    @DisplayName("기록 부족이면 LOW_DATA")
    void resolve_lowData() {
        assertThat(resolver.resolve(true, 100_000, true, true)).isEqualTo(FeedbackType.LOW_DATA);
    }

    @Test
    @DisplayName("savingsImpact 음수(초과 위험)이면 ALERT")
    void resolve_negativeImpact_alert() {
        assertThat(resolver.resolve(false, -50_000, false, false)).isEqualTo(FeedbackType.ALERT);
    }

    @Test
    @DisplayName("HIGH 이상치 있으면 ALERT")
    void resolve_highAnomaly_alert() {
        assertThat(resolver.resolve(false, 100_000, true, false)).isEqualTo(FeedbackType.ALERT);
    }

    @Test
    @DisplayName("예산 초과 카테고리 있으면 ALERT")
    void resolve_overBudget_alert() {
        assertThat(resolver.resolve(false, 100_000, false, true)).isEqualTo(FeedbackType.ALERT);
    }

    @Test
    @DisplayName("신호 없으면 POSITIVE")
    void resolve_noSignal_positive() {
        assertThat(resolver.resolve(false, 100_000, false, false)).isEqualTo(FeedbackType.POSITIVE);
    }

    @Test
    @DisplayName("savingsImpact 0이면 POSITIVE (정확히 맞춤)")
    void resolve_zeroImpact_positive() {
        assertThat(resolver.resolve(false, 0, false, false)).isEqualTo(FeedbackType.POSITIVE);
    }
}
