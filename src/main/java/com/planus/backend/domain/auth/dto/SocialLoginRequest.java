package com.planus.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 소셜 로그인 요청 DTO. 프론트에서 전달받은 인가코드와 리다이렉트 URI를 담는다. */
public record SocialLoginRequest(@NotBlank String code, @NotBlank String redirectUri) {}
