package com.planus.backend.domain.user.dto;

/**
 * 사용자 프로필 저장 응답 DTO.
 *
 * @param userId           사용자 ID
 * @param nickname         닉네임
 * @param availableBudget  가용예산 (소득 − 고정지출 − 저축목표)
 * @param profileCompleted 프로필 입력 완료 여부
 * @param message          안내 메시지
 */
public record UserProfileResponse(
        Long userId, String nickname, long availableBudget, boolean profileCompleted, String message) {}
