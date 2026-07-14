package com.planus.backend.domain.aifeedback.core;

/**
 * 불확실성 밴드 폭. [2] 페이싱 비교와 [7] 렌더링 톤 제어에 사용된다.
 *
 * <p>월초에는 데이터가 적어 밴드가 넓고(WIDE) 단정 표현을 회피하며,
 * 월말에는 밴드가 좁아(NARROW) 구체적인 경고가 가능하다.
 */
public enum BandWidth {
    /** 월초 또는 과거 1개월: 데이터 부족, 단정 회피 톤 */
    WIDE,
    /** 중간 시점 또는 과거 2개월: 범위로 표현, 추이 관찰 */
    MEDIUM,
    /** 월말 + 과거 3개월+: 데이터 충분, 구체적 경고 가능 */
    NARROW
}
