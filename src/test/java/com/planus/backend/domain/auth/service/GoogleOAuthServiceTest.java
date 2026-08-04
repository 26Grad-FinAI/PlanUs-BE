package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.SocialLoginRequest;
import com.planus.backend.domain.user.UserAccountPersister;
import com.planus.backend.domain.user.entity.AuthProvider;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GoogleOAuthServiceTest {

    private UserAccountRepository userAccountRepository;
    private UserAccountPersister userAccountPersister;
    private JwtProvider jwtProvider;
    private GoogleOAuthService googleOAuthService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        userAccountPersister = mock(UserAccountPersister.class);
        jwtProvider = mock(JwtProvider.class);
        googleOAuthService = spy(new GoogleOAuthService(
                userAccountRepository,
                userAccountPersister,
                jwtProvider,
                mock(RestClient.class),
                "client-id",
                "client-secret",
                "https://oauth2.googleapis.com/token",
                "https://www.googleapis.com/oauth2/v3/userinfo",
                List.of("http://localhost/callback")));
    }

    private SocialLoginRequest validRequest() {
        return new SocialLoginRequest("auth-code", "http://localhost/callback");
    }

    private GoogleOAuthService.GoogleUserInfo userInfo() {
        return new GoogleOAuthService.GoogleUserInfo("sub123", "user@example.com", "홍길동", true);
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
            when(userAccountPersister.saveAndFlush(any())).thenReturn(spyUser);
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
            verify(userAccountPersister, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("동시 요청으로 중복 삽입이 발생하면 이미 저장된 사용자를 반환한다")
        void login_concurrentSignup_returnsExistingUser() {
            stubOAuthCalls();
            UserAccount existingUser = spy(UserAccount.builder()
                    .id(3L)
                    .email("user@example.com")
                    .provider(AuthProvider.GOOGLE)
                    .providerId("sub123")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "sub123"))
                    .thenReturn(Optional.empty())          // 첫 조회: 없음
                    .thenReturn(Optional.of(existingUser)); // 중복 키 후 재조회: 있음
            when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
            when(userAccountPersister.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
            when(jwtProvider.generateAccessToken(3L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(3L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = googleOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(3L);
            assertThat(response.accessToken()).isEqualTo("access-token");
            verify(existingUser).updateRefreshToken("hashed-token");
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
        @DisplayName("Google 이메일 미인증 계정이면 UNVERIFIED_SOCIAL_EMAIL 예외가 발생한다")
        void login_unverifiedEmail_throwsUnverifiedSocialEmail() {
            GoogleOAuthService.GoogleUserInfo unverifiedUserInfo =
                    new GoogleOAuthService.GoogleUserInfo("sub123", "user@example.com", "홍길동", false);
            doReturn("google-access-token").when(googleOAuthService).fetchAccessToken(anyString(), anyString());
            doReturn(unverifiedUserInfo).when(googleOAuthService).fetchUserInfo("google-access-token");

            assertThatThrownBy(() -> googleOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.UNVERIFIED_SOCIAL_EMAIL));
        }

        @Test
        @DisplayName("허용되지 않은 redirectUri면 INVALID_REDIRECT_URI 예외가 발생한다")
        void login_invalidRedirectUri_throwsInvalidRedirectUri() {
            assertThatThrownBy(() -> googleOAuthService.login(
                            new SocialLoginRequest("auth-code", "http://evil.com/callback")))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_REDIRECT_URI));
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

        @Test
        @DisplayName("Google 서버 오류(5xx)면 SOCIAL_LOGIN_UNAVAILABLE 예외가 발생한다")
        void login_googleServerError_throwsSocialLoginUnavailable() {
            doThrow(new GeneralException(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE,
                            new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)))
                    .when(googleOAuthService).fetchAccessToken(anyString(), anyString());

            assertThatThrownBy(() -> googleOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE));
        }

        @Test
        @DisplayName("Google 응답 타임아웃이면 SOCIAL_LOGIN_UNAVAILABLE 예외가 발생한다")
        void login_googleTimeout_throwsSocialLoginUnavailable() {
            doThrow(new GeneralException(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE,
                            new ResourceAccessException("timeout")))
                    .when(googleOAuthService).fetchAccessToken(anyString(), anyString());

            assertThatThrownBy(() -> googleOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE));
        }
    }
}
