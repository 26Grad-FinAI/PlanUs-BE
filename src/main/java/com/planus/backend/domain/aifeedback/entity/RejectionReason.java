package com.planus.backend.domain.aifeedback.entity;

/** 피드백 거부 사유. reaction이 NOT_HELPFUL일 때 설정. */
public enum RejectionReason {
    DONT_WANT_REDUCE,
    ALREADY_KNOWN,
    TOO_REPETITIVE,
    NOT_ACCURATE,
    OTHER
}
