package com.planus.backend.domain.user.converter;

import com.planus.backend.domain.user.dto.UserProfileResponse;
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
}
