package com.planus.backend.domain.auth.dto;

public record SignUpResponse(
        Long userId, String email, String accessToken, String refreshToken, boolean profileCompleted) {}
