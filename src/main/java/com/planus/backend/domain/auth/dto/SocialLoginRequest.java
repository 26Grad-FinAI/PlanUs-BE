package com.planus.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 소셜 로그인 요청 DTO. 클라이언트 SDK가 발급한 토큰을 담는다. */
public record SocialLoginRequest(@NotBlank String idToken) {}
