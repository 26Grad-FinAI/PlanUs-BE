package com.planus.backend.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 사용자 프로필 저장 요청 DTO.
 *
 * @param nickname             닉네임
 * @param age                  나이 (양수)
 * @param gender               성별
 * @param residence            거주지
 * @param monthlyIncome        월 소득
 * @param monthlyFixedExpenses 월 고정지출
 * @param monthlySavingsGoal   월 저축 목표
 * @param hobbies              취미 목록 (1개 이상, 각 요소는 공백 불가)
 * @param spendingHabit        소비 습관 (SAVING / BALANCED / SPENDING)
 * @param employmentStatus     재직 여부
 * @param homeOwnership        자가 보유 여부
 */
public record UserProfileRequest(
        @NotBlank String nickname,
        @NotNull @Positive Integer age,
        @NotBlank String gender,
        @NotBlank String residence,
        @NotNull Long monthlyIncome,
        @NotNull Long monthlyFixedExpenses,
        @NotNull Long monthlySavingsGoal,
        @NotEmpty List<@NotBlank String> hobbies,
        @NotBlank String spendingHabit,
        @NotNull Boolean employmentStatus,
        @NotNull Boolean homeOwnership) {}
