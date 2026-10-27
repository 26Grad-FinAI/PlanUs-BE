package com.planus.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원가입 응답")
public record SignUpResponse(
        @Schema(description = "사용자 ID", example = "1") Long userId,
        @Schema(description = "이메일", example = "user@example.com") String email,
        @Schema(description = "JWT 액세스 토큰") String accessToken,
        @Schema(description = "JWT 리프레시 토큰") String refreshToken,
        @Schema(description = "프로필 입력 완료 여부", example = "false") boolean profileCompleted) {}
