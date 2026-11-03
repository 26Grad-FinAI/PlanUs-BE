package com.planus.backend.domain.aifeedback.event;

/**
 * 피드백(주간/월간)이 저장된 뒤 발행되는 이벤트.
 *
 * @param userId    대상 사용자 ID
 * @param yearMonth 피드백 대상 연월 (yyyy-MM 형식)
 */
public record FeedbackCreatedEvent(Long userId, String yearMonth) {}
