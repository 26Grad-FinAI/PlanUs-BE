package com.planus.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "비밀번호 재설정 인증코드 검증 요청")
public record PasswordResetVerifyRequest(
        @Schema(description = "이메일", example = "user@example.com") @NotBlank String email,
        @Schema(description = "인증코드 (6자리)", example = "123456") @NotBlank String code) {}
