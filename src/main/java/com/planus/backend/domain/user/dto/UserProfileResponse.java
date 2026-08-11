package com.planus.backend.domain.user.dto;

public record UserProfileResponse(
        Long userId,
        String nickname,
        long availableBudget,
        String message) {}
