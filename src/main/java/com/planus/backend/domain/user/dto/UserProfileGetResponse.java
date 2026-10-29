package com.planus.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "프로필 조회 응답")
public record UserProfileGetResponse(
        @Schema(description = "사용자 ID", example = "1") Long userId,
        @Schema(description = "이메일", example = "user@example.com") String email,
        @Schema(description = "닉네임", example = "홍길동") String nickname,
        @Schema(description = "나이", example = "25") Integer age,
        @Schema(description = "성별", example = "MALE") String gender,
        @Schema(description = "거주지", example = "서울") String residence,
        @Schema(description = "월 소득 (원)", example = "3000000") long monthlyIncome,
        @Schema(description = "월 고정지출 항목 목록") List<FixedExpenseItem> monthlyFixedExpenses,
        @Schema(description = "월 고정지출 합계 (원)", example = "1000000") long totalFixedExpenses,
        @Schema(description = "월 저축 목표 (원)", example = "500000") long monthlySavingsGoal,
        @Schema(description = "가용예산 (소득-고정지출합계-저축목표)", example = "1500000") long availableBudget,
        @Schema(description = "재직 여부", example = "true") Boolean employmentStatus,
        @Schema(description = "자가 보유 여부", example = "false") Boolean homeOwnership,
        @Schema(description = "자기소개서", example = "절약을 중시하는 직장인입니다.") String selfIntroduction,
        @Schema(description = "프로필 입력 완료 여부", example = "true") boolean profileCompleted,
        @Schema(description = "계정 생성일시") LocalDateTime createdAt,
        @Schema(description = "마지막 수정일시") LocalDateTime updatedAt) {}
