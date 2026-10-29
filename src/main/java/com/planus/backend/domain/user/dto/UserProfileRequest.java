package com.planus.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "사용자 프로필 저장/수정 요청")
public record UserProfileRequest(
        @Schema(description = "나이", example = "25") @NotNull @Positive Integer age,
        @Schema(description = "성별", example = "MALE") @NotBlank String gender,
        @Schema(description = "거주지", example = "서울") @NotBlank String residence,
        @Schema(description = "월 소득 (원)", example = "3000000") @NotNull Long monthlyIncome,
        @Schema(description = "월 고정지출 항목 목록") @Valid List<FixedExpenseItem> monthlyFixedExpenses,
        @Schema(description = "월 저축 목표 (원)", example = "500000") @NotNull Long monthlySavingsGoal,
        @Schema(description = "재직 여부", example = "true") @NotNull Boolean employmentStatus,
        @Schema(description = "자가 보유 여부", example = "false") @NotNull Boolean homeOwnership,
        @Schema(description = "자기소개서 (AI 예산 산출에 활용)", example = "절약을 중시하는 직장인입니다.", nullable = true) @Size(max = 2000)
                String selfIntroduction) {}
