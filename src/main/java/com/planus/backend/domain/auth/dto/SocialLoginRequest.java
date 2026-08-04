package com.planus.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SocialLoginRequest(@NotBlank String code, @NotBlank String redirectUri) {}
