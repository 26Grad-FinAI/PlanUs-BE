package com.planus.backend.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UserProfileRequest(
        @NotNull @Positive Integer age,
        @NotBlank String gender,
        @NotBlank String residence,
        @NotNull Long monthlyIncome,
        @NotNull Long monthlyFixedExpenses,
        @NotNull Long monthlySavingsGoal,
        @NotBlank String hobby,
        @NotBlank String spendingHabit,
        @NotNull Boolean employmentStatus,
        @NotNull Boolean homeOwnership) {}
