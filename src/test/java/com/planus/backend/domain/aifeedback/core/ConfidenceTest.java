package com.planus.backend.domain.aifeedback.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConfidenceTest {

    @Nested
    @DisplayName("magnitude 기반 of(double)")
    class MagnitudeOnly {

        @Test
        void high_whenAboveThreshold() {
            assertThat(Confidence.of(4.0)).isEqualTo(Confidence.HIGH);
            assertThat(Confidence.of(5.5)).isEqualTo(Confidence.HIGH);
        }

        @Test
        void medium_whenAboveMiddleThreshold() {
            assertThat(Confidence.of(2.8)).isEqualTo(Confidence.MEDIUM);
            assertThat(Confidence.of(3.9)).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void low_whenBelowAllThresholds() {
            assertThat(Confidence.of(2.7)).isEqualTo(Confidence.LOW);
            assertThat(Confidence.of(0.0)).isEqualTo(Confidence.LOW);
        }
    }

    @Nested
    @DisplayName("magnitude + sampleSize 기반 of(double, int, int, int)")
    class WithSampleSize {

        @Test
        @DisplayName("표본 충분(n≥30)이면 magnitude대로 HIGH")
        void sufficientSamples_highAllowed() {
            assertThat(Confidence.of(4.5, 35, 30, 15)).isEqualTo(Confidence.HIGH);
        }

        @Test
        @DisplayName("표본 중간(15≤n<30)이면 magnitude가 HIGH여도 MEDIUM으로 제한")
        void mediumSamples_cappedToMedium() {
            assertThat(Confidence.of(4.5, 20, 30, 15)).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        @DisplayName("표본 부족(n<15)이면 magnitude가 HIGH여도 LOW로 제한")
        void lowSamples_cappedToLow() {
            assertThat(Confidence.of(4.5, 10, 30, 15)).isEqualTo(Confidence.LOW);
        }

        @Test
        @DisplayName("magnitude가 LOW이면 표본 충분해도 LOW")
        void lowMagnitude_staysLow() {
            assertThat(Confidence.of(1.0, 50, 30, 15)).isEqualTo(Confidence.LOW);
        }

        @Test
        @DisplayName("magnitude MEDIUM + 표본 충분 → MEDIUM")
        void mediumMagnitude_sufficientSamples() {
            assertThat(Confidence.of(3.0, 35, 30, 15)).isEqualTo(Confidence.MEDIUM);
        }
    }

    @Nested
    @DisplayName("lower() — ordinal 기반 비교")
    class LowerTest {

        @Test
        @DisplayName("HIGH vs LOW → LOW")
        void highVsLow() {
            assertThat(Confidence.lower(Confidence.HIGH, Confidence.LOW)).isEqualTo(Confidence.LOW);
        }

        @Test
        @DisplayName("LOW vs HIGH → LOW")
        void lowVsHigh() {
            assertThat(Confidence.lower(Confidence.LOW, Confidence.HIGH)).isEqualTo(Confidence.LOW);
        }

        @Test
        @DisplayName("MEDIUM vs MEDIUM → MEDIUM")
        void sameLevel() {
            assertThat(Confidence.lower(Confidence.MEDIUM, Confidence.MEDIUM)).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        @DisplayName("HIGH vs MEDIUM → MEDIUM")
        void highVsMedium() {
            assertThat(Confidence.lower(Confidence.HIGH, Confidence.MEDIUM)).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        @DisplayName("교환법칙: lower(a, b) == lower(b, a)")
        void commutative() {
            for (Confidence a : Confidence.values()) {
                for (Confidence b : Confidence.values()) {
                    assertThat(Confidence.lower(a, b))
                            .as("lower(%s, %s) == lower(%s, %s)", a, b, b, a)
                            .isEqualTo(Confidence.lower(b, a));
                }
            }
        }

        @Test
        @DisplayName("ordinal 순서: HIGH(0) < MEDIUM(1) < LOW(2) — 역전 시 lower() 오동작")
        void ordinal_order_invariant() {
            assertThat(Confidence.HIGH.ordinal()).isLessThan(Confidence.MEDIUM.ordinal());
            assertThat(Confidence.MEDIUM.ordinal()).isLessThan(Confidence.LOW.ordinal());
        }
    }
}
