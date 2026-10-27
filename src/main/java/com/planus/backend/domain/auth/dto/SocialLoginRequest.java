package com.planus.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "소셜 로그인 요청")
public record SocialLoginRequest(
        @Schema(description = "클라이언트 SDK가 발급한 토큰 (Google: id_token, Kakao: access_token)") @NotBlank String idToken) {}
