package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.SocialLoginRequest;
import com.planus.backend.domain.user.entity.AuthProvider;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GoogleOAuthServiceTest {

    private UserAccountRepository userAccountRepository;
    private JwtProvider jwtProvider;
    private GoogleOAuthService googleOAuthService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        jwtProvider = mock(JwtProvider.class);
        googleOAuthService = spy(new GoogleOAuthService(
                userAccountRepository,
                jwtProvider,
                mock(RestClient.class),
                "client-id",
                "client-secret",
                "https://oauth2.googleapis.com/token",
                "https://www.googleapis.com/oauth2/v3/userinfo"));
    }

    private SocialLoginRequest validRequest() {
        return new SocialLoginRequest("auth-code", "http://localhost/callback");
    }

    private GoogleOAuthService.GoogleUserInfo userInfo() {
        return new GoogleOAuthService.GoogleUserInfo("sub123", "user@example.com", "홍길동");
    }

    private void stubOAuthCalls() {
        doReturn("google-access-token").when(googleOAuthService).fetchAccessToken(anyString(), anyString());
        doReturn(userInfo()).when(googleOAuthService).fetchUserInfo("google-access-token");
    }

    @Nested
    @DisplayName("로그인 성공")
    class LoginSuccess {

        @Test
        @DisplayName("신규 Google 사용자는 DB에 저장되고 JWT가 발급된다")
        void login_newUser_savesAndReturnsTokens() {
            stubOAuthCalls();
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "sub123"))
                    .thenReturn(Optional.empty());
            when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

            UserAccount spyUser = spy(UserAccount.builder()
                    .id(1L)
                    .email("user@example.com")
                    .nickname("홍길동")
                    .provider(AuthProvider.GOOGLE)
                    .providerId("sub123")
                    .build());
            when(userAccountRepository.save(any())).thenReturn(spyUser);
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = googleOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("user@example.com");
            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(response.refreshToken()).isEqualTo("refresh-token");
            verify(spyUser).updateRefreshToken("hashed-token");
        }

        @Test
        @DisplayName("기존 Google 사용자는 DB 저장 없이 JWT만 발급된다")
        void login_existingUser_returnsTokensWithoutSave() {
            stubOAuthCalls();
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(2L)
                    .email("user@example.com")
                    .provider(AuthProvider.GOOGLE)
                    .providerId("sub123")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "sub123"))
                    .thenReturn(Optional.of(spyUser));
            when(jwtProvider.generateAccessToken(2L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(2L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = googleOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(2L);
            assertThat(response.accessToken()).isEqualTo("access-token");
            verify(spyUser).updateRefreshToken("hashed-token");
        }
    }

    @Nested
    @DisplayName("로그인 실패")
    class LoginFailure {

        @Test
        @DisplayName("이미 다른 방식으로 가입된 이메일이면 SOCIAL_LOGIN_EMAIL_CONFLICT 예외가 발생한다")
        void login_emailConflict_throwsSocialLoginEmailConflict() {
            stubOAuthCalls();
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "sub123"))
                    .thenReturn(Optional.empty());
            when(userAccountRepository.findByEmail("user@example.com"))
                    .thenReturn(Optional.of(UserAccount.builder()
                            .id(99L)
                            .email("user@example.com")
                            .provider(AuthProvider.LOCAL)
                            .build()));

            assertThatThrownBy(() -> googleOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.SOCIAL_LOGIN_EMAIL_CONFLICT));
        }

        @Test
        @DisplayName("유효하지 않은 인가코드면 INVALID_CREDENTIALS 예외가 발생한다")
        void login_invalidCode_throwsInvalidCredentials() {
            doThrow(new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS))
                    .when(googleOAuthService).fetchAccessToken(anyString(), anyString());

            assertThatThrownBy(() -> googleOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CREDENTIALS));
        }
    }
}
