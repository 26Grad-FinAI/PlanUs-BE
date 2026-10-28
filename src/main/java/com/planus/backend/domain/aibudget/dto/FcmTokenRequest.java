package com.planus.backend.domain.aibudget.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "FCM 디바이스 토큰 등록 요청")
public record FcmTokenRequest(@Schema(description = "FCM 토큰", example = "dAPx...") @NotBlank String fcmToken) {}
