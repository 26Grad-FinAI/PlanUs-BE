package com.planus.backend.domain.user.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 프로필 조회 응답 DTO.
 *
 * @param userId               사용자 ID
 * @param email                이메일
 * @param nickname             닉네임
 * @param age                  나이
 * @param gender               성별
 * @param residence            거주지
 * @param monthlyIncome        월 소득
 * @param monthlyFixedExpenses 월 고정지출
 * @param monthlySavingsGoal   월 저축 목표
 * @param availableBudget      가용예산 (소득 − 고정지출 − 저축목표)
 * @param hobbies              취미 목록
 * @param spendingHabit        소비 습관
 * @param employmentStatus     재직 여부
 * @param homeOwnership        자가 보유 여부
 * @param profileCompleted     프로필 입력 완료 여부
 * @param createdAt            계정 생성일시
 * @param updatedAt            마지막 수정일시
 */
public record UserProfileGetResponse(
        Long userId,
        String email,
        String nickname,
        Integer age,
        String gender,
        String residence,
        long monthlyIncome,
        long monthlyFixedExpenses,
        long monthlySavingsGoal,
        long availableBudget,
        List<String> hobbies,
        String spendingHabit,
        Boolean employmentStatus,
        Boolean homeOwnership,
        boolean profileCompleted,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
