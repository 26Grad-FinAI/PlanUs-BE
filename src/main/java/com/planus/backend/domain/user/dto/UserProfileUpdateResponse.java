package com.planus.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "프로필 수정 응답")
public record UserProfileUpdateResponse(
        @Schema(description = "사용자 ID", example = "1") Long userId,
        @Schema(description = "가용예산 (소득-고정지출-저축목표)", example = "1500000") long availableBudget,
        @Schema(description = "안내 메시지") String message,
        @Schema(description = "프로필 마지막 수정일시") LocalDateTime updatedAt) {}
