package com.planus.backend.domain.auth.converter;

import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Auth 도메인의 엔티티 ↔ DTO 변환 유틸리티. 인스턴스화 불가. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthConverter {

    /**
     * 회원가입 요청 DTO를 {@link UserAccount} 엔티티로 변환한다.
     *
     * @param request        회원가입 요청 DTO
     * @param encodedPassword BCrypt 암호화된 비밀번호
     * @return 저장 전 UserAccount 엔티티
     */
    public static UserAccount toUserAccount(SignUpRequest request, String encodedPassword) {
        return UserAccount.builder()
                .email(request.email())
                .nickname(request.nickname())
                .password(encodedPassword)
                .build();
    }

    /**
     * {@link UserAccount}와 JWT 토큰을 회원가입 응답 DTO로 변환한다.
     *
     * @param user         저장된 사용자 엔티티
     * @param accessToken  발급된 액세스 토큰
     * @param refreshToken 발급된 리프레시 토큰
     * @return 회원가입 응답 DTO
     */
    public static SignUpResponse toSignUpResponse(UserAccount user, String accessToken, String refreshToken) {
        return new SignUpResponse(user.getId(), user.getEmail(), accessToken, refreshToken, user.isProfileCompleted());
    }

    /**
     * {@link UserAccount}와 JWT 토큰을 로그인 응답 DTO로 변환한다.
     *
     * @param user         인증된 사용자 엔티티
     * @param accessToken  발급된 액세스 토큰
     * @param refreshToken 발급된 리프레시 토큰
     * @return 로그인 응답 DTO
     */
    public static LoginResponse toLoginResponse(UserAccount user, String accessToken, String refreshToken) {
        return new LoginResponse(user.getId(), user.getEmail(), accessToken, refreshToken, user.isProfileCompleted());
    }
}
