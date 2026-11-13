package com.planus.backend.domain.user.event;

/**
 * 프로필 저장·수정이 끝난 뒤 발행되는 이벤트.
 *
 * @param userId 대상 사용자 ID
 */
public record ProfileSavedEvent(Long userId) {}
