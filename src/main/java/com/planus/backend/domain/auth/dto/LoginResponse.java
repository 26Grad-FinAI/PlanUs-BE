package com.planus.backend.domain.auth.dto;

public record LoginResponse(
        Long userId,
        String email,
        String accessToken,
        String refreshToken,
        boolean profileCompleted) {}
