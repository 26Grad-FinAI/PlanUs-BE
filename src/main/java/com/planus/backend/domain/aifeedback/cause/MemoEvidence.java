package com.planus.backend.domain.aifeedback.cause;

import java.time.LocalDate;

/**
 * 메모 원문 + 맥락 정보. LLM 렌더러에 전달하기 위한 구조체.
 *
 * @param date   거래 일자 (spent_at 기준)
 * @param amount 거래 금액 (원)
 * @param text   메모 원문
 */
public record MemoEvidence(LocalDate date, long amount, String text) {}
