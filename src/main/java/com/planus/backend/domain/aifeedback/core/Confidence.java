package com.planus.backend.domain.aifeedback.core;

/**
 * 이상치 신뢰도 — 통계량으로만 정의 (메모 유무와 독립).
 * 메모 유무는 [5]에서 "원인 단정 가능 여부"를 결정하는 별개 축.
 *   HIGH  : magnitude ≥ 4.0 → 단독 알림 가능
 *   MEDIUM: magnitude ≥ 2.8 → 근거 보강용
 *   LOW   : 나머지
 */
public enum Confidence {
    HIGH,
    MEDIUM,
    LOW;

    private static final double HIGH_THRESHOLD = 4.0;
    private static final double MEDIUM_THRESHOLD = 2.8;

    /**
     * 이상치 강도(magnitude)로 신뢰도를 결정한다.
     *
     * @param magnitude 이상치 크기 (robust z-score 또는 중앙값 대비 배수)
     * @return 산출된 신뢰도 밴드
     */
    public static Confidence of(double magnitude) {
        if (magnitude >= HIGH_THRESHOLD) return HIGH;
        if (magnitude >= MEDIUM_THRESHOLD) return MEDIUM;
        return LOW;
    }
}
