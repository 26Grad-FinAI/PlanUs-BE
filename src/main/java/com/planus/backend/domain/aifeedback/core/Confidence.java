package com.planus.backend.domain.aifeedback.core;

/**
 * 신뢰도 밴드 — 규칙 기반(LLM 자기보고 % 폐기).
 * 이상치 단독 알림은 HIGH일 때만 사용자에게 노출(base-rate 한계 대응).
 *   HIGH  : 이상치 큼(배수≥기준) + 동일테마 선례 다수(≥기준)
 *   MEDIUM: 선례 일부
 *   LOW   : 이상은 있으나 과거 근거 부족 → 단독 노출 금지
 */
public enum Confidence {
    HIGH, MEDIUM, LOW;

    public static Confidence of(double anomalyMagnitude, int strongPrecedents,
                                double highMag, int highPrecedents) {
        if (anomalyMagnitude >= highMag && strongPrecedents >= highPrecedents) return HIGH;
        if (strongPrecedents >= 1) return MEDIUM;
        return LOW;
    }
}
