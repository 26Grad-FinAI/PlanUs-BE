package com.planus.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "비밀번호 재설정 인증코드 발송 요청")
public record PasswordResetSendRequest(
        @Schema(description = "이메일", example = "user@example.com") @NotBlank String email) {}
