package com.planus.backend.domain.user.service;

import com.planus.backend.domain.user.converter.UserConverter;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
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
        UserAccount user =
                userAccountRepository
                        .findById(userId)
                        .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        user.updateProfile(
                request.age(),
                request.gender(),
                request.residence(),
                request.monthlyIncome(),
                request.monthlyFixedExpenses(),
                request.monthlySavingsGoal(),
                request.hobby(),
                request.spendingHabit(),
                request.employmentStatus(),
                request.homeOwnership());

        return UserConverter.toProfileResponse(user);
    }
}
