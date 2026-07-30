package com.planus.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SignUpRequest(
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank String confirmPassword,
        @NotBlank String nickname,
        boolean agreeToTerms) {}
