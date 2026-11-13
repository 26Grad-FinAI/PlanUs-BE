package com.planus.backend.domain.aifeedback.cause;

import java.time.LocalDate;

/**
 * 메모 원문 + 맥락 정보. LLM 렌더러에 전달하기 위한 구조체.
 *
 * @param date   거래 일자 (spent_at 기준)
 * @param amount 거래 1건 금액 (원)
 * @param text   메모 원문
 * @param count  같은 날·같은 금액·같은 메모로 묶인 거래 건수 (#111 — 목록 줄 수를 횟수로 착각하지 않도록 묶어서 전달)
 */
public record MemoEvidence(LocalDate date, long amount, String text, int count) {

    /** 1건짜리 메모. */
    public MemoEvidence(LocalDate date, long amount, String text) {
        this(date, amount, text, 1);
    }
}
