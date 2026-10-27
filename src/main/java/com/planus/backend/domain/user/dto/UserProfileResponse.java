package com.planus.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로필 저장 응답")
public record UserProfileResponse(
        @Schema(description = "사용자 ID", example = "1") Long userId,
        @Schema(description = "닉네임", example = "홍길동") String nickname,
        @Schema(description = "가용예산 (소득-고정지출-저축목표)", example = "1500000") long availableBudget,
        @Schema(description = "프로필 입력 완료 여부", example = "true") boolean profileCompleted,
        @Schema(description = "안내 메시지") String message) {}
