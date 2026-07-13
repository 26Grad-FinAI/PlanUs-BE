package com.planus.backend.domain.aifeedback.core;

/**
 * 피드백 유형. [4] FeedbackTypeResolver에서 결정되며, 항상 레코드가 생성된다.
 *
 * <p>침묵(레코드 미생성)은 사용자에게 "기능 고장", 운영에게 "실패 vs 스킵 구분 불가"이므로
 * 모든 주간 실행은 세 유형 중 하나로 저장된다.
 */
public enum FeedbackType {
    /** 저축 위험 / HIGH 이상치 / 예산 초과 → 푸시 */
    ALERT,
    /** 신호 없음 → 인앱 배지 */
    POSITIVE,
    /** [1.5] 기록 부족 → 기록 리마인드 */
    LOW_DATA
}
