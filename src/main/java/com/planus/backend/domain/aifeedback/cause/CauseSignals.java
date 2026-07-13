package com.planus.backend.domain.aifeedback.cause;

import java.util.List;
import java.util.Map;

/**
 * [5] 원인 신호 조립 결과. 수치·신호만 담고, 문장 생성은 [7] LLM 렌더러의 책임.
 *
 * @param selfRatio          본인 과거 7주 대비 이번 주 배율. null이면 산출 불가(과거 부족 등).
 * @param dominantFactor     "FREQUENCY" / "UNIT_PRICE" / "BOTH" / null
 * @param freqContribution   빈도 기여율 (0~1). 증가 방향만 반영.
 * @param priceContribution  단가 기여율 (0~1). 증가 방향만 반영.
 * @param emotionCounts      이번 주 변동 지출의 감정태그별 건수
 * @param repeatStreak       프로필 반복 패턴의 streak 값. null이면 패턴 미등록.
 * @param memos              이번 주 + 지난주 메모 원문 (최신순, LIMIT 10)
 * @param hasMemo            메모 존재 여부
 */
public record CauseSignals(
        Double selfRatio,
        String dominantFactor,
        double freqContribution,
        double priceContribution,
        Map<String, Long> emotionCounts,
        Integer repeatStreak,
        List<MemoEvidence> memos,
        boolean hasMemo) {}
