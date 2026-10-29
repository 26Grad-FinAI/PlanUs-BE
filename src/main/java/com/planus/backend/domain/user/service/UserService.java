package com.planus.backend.domain.user.service;

import com.planus.backend.domain.user.converter.UserConverter;
import com.planus.backend.domain.user.dto.UserProfileGetResponse;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.dto.UserProfileUpdateResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 프로필 비즈니스 로직. */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserAccountRepository userAccountRepository;

    /**
     * 사용자 프로필을 저장하고 가용예산을 반환한다.
     *
     * @param userId  JWT 필터가 주입한 사용자 ID
     * @param request 프로필 요청 DTO
     * @return 저장된 사용자 정보 및 가용예산
     */
    @Transactional
    public UserProfileResponse saveProfile(Long userId, UserProfileRequest request) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        user.updateProfile(
                request.age(),
                request.gender(),
                request.residence(),
                request.monthlyIncome(),
                request.monthlyFixedExpenses(),
                request.monthlySavingsGoal(),
                request.employmentStatus(),
                request.homeOwnership(),
                request.selfIntroduction());

        return UserConverter.toProfileResponse(user);
    }

    /**
     * 사용자 프로필을 수정한다.
     *
     * @param userId  JWT 필터가 주입한 사용자 ID
     * @param request 수정할 프로필 요청 DTO
     * @return 수정된 가용예산 및 수정일시
     */
    @Transactional
    public UserProfileUpdateResponse updateProfile(Long userId, UserProfileRequest request) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        user.updateProfile(
                request.age(),
                request.gender(),
                request.residence(),
                request.monthlyIncome(),
                request.monthlyFixedExpenses(),
                request.monthlySavingsGoal(),
                request.employmentStatus(),
                request.homeOwnership(),
                request.selfIntroduction());

        return UserConverter.toProfileUpdateResponse(user);
    }

    /**
     * FCM 디바이스 토큰을 갱신한다.
     *
     * @param userId   JWT 필터가 주입한 사용자 ID
     * @param fcmToken FCM 디바이스 토큰
     */
    @Transactional
    public void updateFcmToken(Long userId, String fcmToken) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));
        user.updateFcmToken(fcmToken);
    }

    /**
     * 사용자 프로필을 조회한다.
     *
     * @param userId JWT 필터가 주입한 사용자 ID
     * @return 사용자 프로필 전체 정보
     */
    @Transactional(readOnly = true)
    public UserProfileGetResponse getProfile(Long userId) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        return UserConverter.toProfileGetResponse(user);
    }
}
