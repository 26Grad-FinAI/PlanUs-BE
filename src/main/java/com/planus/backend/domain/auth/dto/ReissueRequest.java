package com.planus.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 토큰 재발급 요청 DTO. 클라이언트가 보유한 리프레시 토큰을 담는다. */
public record ReissueRequest(@NotBlank String refreshToken) {}
