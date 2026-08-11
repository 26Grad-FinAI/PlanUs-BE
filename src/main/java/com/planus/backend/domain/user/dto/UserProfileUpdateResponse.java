package com.planus.backend.domain.user.dto;

import java.time.LocalDateTime;

/**
 * 사용자 프로필 수정 응답 DTO.
 *
 * @param userId          사용자 ID
 * @param availableBudget 가용예산 (소득 − 고정지출 − 저축목표)
 * @param message         안내 메시지
 * @param updatedAt       프로필 마지막 수정일시
 */
public record UserProfileUpdateResponse(
        Long userId,
        long availableBudget,
        String message,
        LocalDateTime updatedAt) {}
