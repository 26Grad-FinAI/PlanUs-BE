package com.planus.backend.domain.aifeedback.core;

/**
 * 이상치 신뢰도 — 통계량 + 표본 크기로 정의 (메모 유무와 독립).
 *
 * <p>두 축으로 결정된다:
 * <ul>
 *   <li><b>magnitude 축</b>: HIGH(≥4.0), MEDIUM(≥2.8), LOW(나머지)
 *   <li><b>표본 크기 축</b>: n≥high → HIGH 가능, n≥medium → MEDIUM까지, n&lt;medium → LOW까지
 * </ul>
 *
 * <p>메모 유무는 [5]에서 "원인 단정 가능 여부"를 결정하는 별개 축.
 */
public enum Confidence {
    HIGH,
    MEDIUM,
    LOW;

    private static final double HIGH_THRESHOLD = 4.0;
    private static final double MEDIUM_THRESHOLD = 2.8;

    /**
     * 이상치 강도(magnitude)만으로 신뢰도를 결정한다. 표본 크기 제한 없음.
     *
     * @param magnitude 이상치 크기 (robust z-score 또는 중앙값 대비 배수)
     * @return 산출된 신뢰도 밴드
     */
    public static Confidence of(double magnitude) {
        if (magnitude >= HIGH_THRESHOLD) return HIGH;
        if (magnitude >= MEDIUM_THRESHOLD) return MEDIUM;
        return LOW;
    }

    /**
     * 이상치 강도와 표본 크기를 모두 고려하여 신뢰도를 결정한다.
     *
     * <p>magnitude 기반 등급을 표본 크기 상한으로 제한한다.
     * 표본이 얕으면 탐지는 하되 확신을 낮춰, 단독 푸시를 방지하고 근거 보강용으로만 사용한다.
     *
     * @param magnitude        이상치 크기
     * @param sampleSize       해당 카테고리의 과거 거래 건수
     * @param highMinSamples   HIGH 허용 최소 표본 수 (설정값)
     * @param mediumMinSamples MEDIUM 허용 최소 표본 수 (설정값)
     * @return 두 축 중 낮은 쪽의 신뢰도
     */
    public static Confidence of(double magnitude, int sampleSize, int highMinSamples, int mediumMinSamples) {
        Confidence base = of(magnitude);
        Confidence cap = capBySampleSize(sampleSize, highMinSamples, mediumMinSamples);
        return lower(base, cap);
    }

    /**
     * 표본 크기로 허용 가능한 최대 신뢰도를 반환한다.
     *
     * @param sampleSize       과거 거래 건수
     * @param highMinSamples   HIGH 허용 최소 표본 수
     * @param mediumMinSamples MEDIUM 허용 최소 표본 수
     * @return 허용 최대 신뢰도
     */
    public static Confidence capBySampleSize(int sampleSize, int highMinSamples, int mediumMinSamples) {
        if (sampleSize >= highMinSamples) return HIGH;
        if (sampleSize >= mediumMinSamples) return MEDIUM;
        return LOW;
    }

    /**
     * 두 신뢰도 중 낮은 쪽을 반환한다. ordinal이 클수록 낮은 신뢰도(HIGH=0, MEDIUM=1, LOW=2).
     *
     * @param a 첫 번째 신뢰도
     * @param b 두 번째 신뢰도
     * @return 둘 중 낮은 신뢰도
     */
    public static Confidence lower(Confidence a, Confidence b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
