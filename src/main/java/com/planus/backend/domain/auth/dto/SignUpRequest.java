package com.planus.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "회원가입 요청")
public record SignUpRequest(
        @Schema(description = "이메일", example = "user@example.com") @NotBlank String email,
        @Schema(description = "닉네임", example = "홍길동") @NotBlank String nickname,
        @Schema(description = "비밀번호", example = "password123!") @NotBlank String password,
        @Schema(description = "비밀번호 확인", example = "password123!") @NotBlank String confirmPassword,
        @Schema(description = "약관 동의 여부", example = "true") boolean agreeToTerms) {}
