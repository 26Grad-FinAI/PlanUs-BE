package com.planus.backend.domain.auth.converter;

import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthConverter {

    public static UserAccount toUserAccount(SignUpRequest request, String encodedPassword) {
        return UserAccount.builder()
                .email(request.email())
                .nickname(request.nickname())
                .password(encodedPassword)
                .build();
    }

    public static SignUpResponse toSignUpResponse(
            UserAccount user, String accessToken, String refreshToken) {
        return new SignUpResponse(
                user.getId(),
                user.getEmail(),
                accessToken,
                refreshToken,
                user.isProfileCompleted());
    }
}
