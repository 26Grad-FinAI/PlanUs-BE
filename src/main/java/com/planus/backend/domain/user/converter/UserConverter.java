package com.planus.backend.domain.user.converter;

import com.planus.backend.domain.user.dto.UserProfileGetResponse;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.dto.UserProfileUpdateResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** User 도메인의 엔티티 ↔ DTO 변환 유틸리티. 인스턴스화 불가. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserConverter {

    /**
     * {@link UserAccount}를 프로필 저장 응답 DTO로 변환한다.
     *
     * @param user 저장된 사용자 엔티티
     * @return 프로필 저장 응답 DTO
     */
    public static UserProfileResponse toProfileResponse(UserAccount user) {
        return new UserProfileResponse(
                user.getId(),
                user.getNickname(),
                user.availableBudget(),
                user.isProfileCompleted(),
                "프로필 저장 완료. AI 예산 산출이 시작됩니다.");
    }

    /**
     * {@link UserAccount}를 프로필 수정 응답 DTO로 변환한다.
     *
     * @param user 수정된 사용자 엔티티
     * @return 프로필 수정 응답 DTO
     */
    public static UserProfileUpdateResponse toProfileUpdateResponse(UserAccount user) {
        return new UserProfileUpdateResponse(
                user.getId(), user.availableBudget(), "프로필 수정 완료. 예산 재산출이 트리거됩니다.", user.getUpdatedAt());
    }

    /**
     * {@link UserAccount}를 프로필 조회 응답 DTO로 변환한다.
     *
     * @param user 조회된 사용자 엔티티
     * @return 프로필 조회 응답 DTO
     */
    public static UserProfileGetResponse toProfileGetResponse(UserAccount user) {
        return new UserProfileGetResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getAge(),
                user.getGender(),
                user.getResidence(),
                user.getMonthlyIncome(),
                user.getMonthlyFixedExpenses(),
                user.getMonthlySavingsGoal(),
                user.availableBudget(),
                user.getHobbies(),
                user.getSpendingHabit(),
                user.getEmploymentStatus(),
                user.getHomeOwnership(),
                user.isProfileCompleted(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
