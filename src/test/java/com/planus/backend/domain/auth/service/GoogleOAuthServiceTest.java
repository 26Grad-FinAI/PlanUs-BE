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

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.SocialLoginRequest;
import com.planus.backend.domain.user.UserAccountPersister;
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
import org.springframework.dao.DataIntegrityViolationException;

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
        googleOAuthService =
                spy(new GoogleOAuthService(userAccountRepository, userAccountPersister, jwtProvider, "test-client-id"));
    }

    private SocialLoginRequest validRequest() {
        return new SocialLoginRequest("valid-id-token");
    }

    private GoogleIdToken.Payload validPayload() {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("sub123");
        payload.setEmail("user@example.com");
        payload.setEmailVerified(true);
        payload.set("name", "홍길동");
        return payload;
    }

    private void stubIdTokenVerification() {
        doReturn(validPayload()).when(googleOAuthService).verifyIdToken(anyString());
    }

    @Nested
    @DisplayName("로그인 성공")
    class LoginSuccess {

        @Test
        @DisplayName("신규 Google 사용자는 DB에 저장되고 JWT가 발급된다")
        void login_newUser_savesAndReturnsTokens() {
            stubIdTokenVerification();
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(1L)
                    .email("user@example.com")
                    .nickname("홍길동")
                    .provider(AuthProvider.GOOGLE)
                    .providerId("sub123")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "sub123"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(spyUser));
            when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
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
            stubIdTokenVerification();
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
            stubIdTokenVerification();
            UserAccount existingUser = spy(UserAccount.builder()
                    .id(3L)
                    .email("user@example.com")
                    .provider(AuthProvider.GOOGLE)
                    .providerId("sub123")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "sub123"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existingUser));
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
            stubIdTokenVerification();
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
            GoogleIdToken.Payload unverifiedPayload = new GoogleIdToken.Payload();
            unverifiedPayload.setSubject("sub123");
            unverifiedPayload.setEmail("user@example.com");
            unverifiedPayload.setEmailVerified(false);
            unverifiedPayload.set("name", "홍길동");
            doReturn(unverifiedPayload).when(googleOAuthService).verifyIdToken(anyString());

            assertThatThrownBy(() -> googleOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.UNVERIFIED_SOCIAL_EMAIL));
        }

        @Test
        @DisplayName("유효하지 않은 id_token이면 INVALID_ID_TOKEN 예외가 발생한다")
        void login_invalidIdToken_throwsInvalidIdToken() {
            doThrow(new GeneralException(GeneralErrorCode.INVALID_ID_TOKEN))
                    .when(googleOAuthService)
                    .verifyIdToken(anyString());

            assertThatThrownBy(() -> googleOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_ID_TOKEN));
        }
    }
}
