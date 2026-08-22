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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class KakaoOAuthServiceTest {

    private UserAccountRepository userAccountRepository;
    private UserAccountPersister userAccountPersister;
    private JwtProvider jwtProvider;
    private KakaoOAuthService kakaoOAuthService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        userAccountPersister = mock(UserAccountPersister.class);
        jwtProvider = mock(JwtProvider.class);
        kakaoOAuthService = spy(new KakaoOAuthService(
                userAccountRepository,
                userAccountPersister,
                jwtProvider,
                mock(RestClient.class),
                "client-id",
                "client-secret",
                "https://kauth.kakao.com/oauth/token",
                "https://kapi.kakao.com/v2/user/me",
                List.of("http://localhost/callback")));
    }

    private SocialLoginRequest validRequest() {
        return new SocialLoginRequest("auth-code", "http://localhost/callback");
    }

    private KakaoOAuthService.KakaoUserInfo userInfo() {
        return new KakaoOAuthService.KakaoUserInfo(
                123456789L,
                new KakaoOAuthService.KakaoAccount(
                        "user@kakao.com", true, true, new KakaoOAuthService.KakaoProfile("홍길동")));
    }

    private void stubOAuthCalls() {
        doReturn("kakao-access-token").when(kakaoOAuthService).fetchAccessToken(anyString(), anyString());
        doReturn(userInfo()).when(kakaoOAuthService).fetchUserInfo("kakao-access-token");
    }

    @Nested
    @DisplayName("로그인 성공")
    class LoginSuccess {

        @Test
        @DisplayName("신규 Kakao 사용자는 DB에 저장되고 JWT가 발급된다")
        void login_newUser_savesAndReturnsTokens() {
            stubOAuthCalls();
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(1L)
                    .email("user@kakao.com")
                    .nickname("홍길동")
                    .provider(AuthProvider.KAKAO)
                    .providerId("123456789")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123456789"))
                    .thenReturn(Optional.empty()) // findOrCreateUser 최초 조회
                    .thenReturn(Optional.of(spyUser)); // saveAndFlush 후 T1 재조회
            when(userAccountRepository.findByEmail("user@kakao.com")).thenReturn(Optional.empty());
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = kakaoOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("user@kakao.com");
            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(response.refreshToken()).isEqualTo("refresh-token");
            verify(spyUser).updateRefreshToken("hashed-token");
        }

        @Test
        @DisplayName("기존 Kakao 사용자는 DB 저장 없이 JWT만 발급된다")
        void login_existingUser_returnsTokensWithoutSave() {
            stubOAuthCalls();
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(2L)
                    .email("user@kakao.com")
                    .provider(AuthProvider.KAKAO)
                    .providerId("123456789")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123456789"))
                    .thenReturn(Optional.of(spyUser));
            when(jwtProvider.generateAccessToken(2L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(2L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = kakaoOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(2L);
            assertThat(response.accessToken()).isEqualTo("access-token");
            verify(spyUser).updateRefreshToken("hashed-token");
            verify(userAccountPersister, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("닉네임 동의를 거부한 신규 사용자는 기본 닉네임으로 저장된다")
        void login_newUser_profileNull_savesWithDefaultNickname() {
            KakaoOAuthService.KakaoUserInfo noProfileUserInfo = new KakaoOAuthService.KakaoUserInfo(
                    123456789L, new KakaoOAuthService.KakaoAccount("user@kakao.com", true, true, null));
            doReturn("kakao-access-token").when(kakaoOAuthService).fetchAccessToken(anyString(), anyString());
            doReturn(noProfileUserInfo).when(kakaoOAuthService).fetchUserInfo("kakao-access-token");
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(1L)
                    .email("user@kakao.com")
                    .nickname("카카오 사용자")
                    .provider(AuthProvider.KAKAO)
                    .providerId("123456789")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123456789"))
                    .thenReturn(Optional.empty()) // findOrCreateUser 최초 조회
                    .thenReturn(Optional.of(spyUser)); // saveAndFlush 후 T1 재조회
            when(userAccountRepository.findByEmail("user@kakao.com")).thenReturn(Optional.empty());
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = kakaoOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(1L);
            verify(spyUser).updateRefreshToken("hashed-token");
        }

        @Test
        @DisplayName("동시 요청으로 중복 삽입이 발생하면 이미 저장된 사용자를 반환한다")
        void login_concurrentSignup_returnsExistingUser() {
            stubOAuthCalls();
            UserAccount existingUser = spy(UserAccount.builder()
                    .id(3L)
                    .email("user@kakao.com")
                    .provider(AuthProvider.KAKAO)
                    .providerId("123456789")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123456789"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existingUser));
            when(userAccountRepository.findByEmail("user@kakao.com")).thenReturn(Optional.empty());
            when(userAccountPersister.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
            when(jwtProvider.generateAccessToken(3L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(3L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = kakaoOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(3L);
            verify(existingUser).updateRefreshToken("hashed-token");
        }
    }

    @Nested
    @DisplayName("로그인 실패")
    class LoginFailure {

        @Test
        @DisplayName("emailVerified == false여도 로그인에 성공한다")
        void login_emailNotVerified_succeeds() {
            KakaoOAuthService.KakaoUserInfo unverifiedUserInfo = new KakaoOAuthService.KakaoUserInfo(
                    123456789L,
                    new KakaoOAuthService.KakaoAccount(
                            "user@kakao.com", false, true, new KakaoOAuthService.KakaoProfile("홍길동")));
            doReturn("kakao-access-token").when(kakaoOAuthService).fetchAccessToken(anyString(), anyString());
            doReturn(unverifiedUserInfo).when(kakaoOAuthService).fetchUserInfo("kakao-access-token");
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(1L)
                    .email("user@kakao.com")
                    .provider(AuthProvider.KAKAO)
                    .providerId("123456789")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123456789"))
                    .thenReturn(Optional.of(spyUser));
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = kakaoOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("emailValid == false여도 로그인에 성공한다")
        void login_emailNotValid_succeeds() {
            KakaoOAuthService.KakaoUserInfo invalidEmailUserInfo = new KakaoOAuthService.KakaoUserInfo(
                    123456789L,
                    new KakaoOAuthService.KakaoAccount(
                            "user@kakao.com", true, false, new KakaoOAuthService.KakaoProfile("홍길동")));
            doReturn("kakao-access-token").when(kakaoOAuthService).fetchAccessToken(anyString(), anyString());
            doReturn(invalidEmailUserInfo).when(kakaoOAuthService).fetchUserInfo("kakao-access-token");
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(1L)
                    .email("user@kakao.com")
                    .provider(AuthProvider.KAKAO)
                    .providerId("123456789")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123456789"))
                    .thenReturn(Optional.of(spyUser));
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = kakaoOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("kakao_account가 없는 신규 사용자는 null 이메일과 기본 닉네임으로 저장된다")
        void login_newUser_kakaoAccountNull_savesWithNullEmailAndDefaultNickname() {
            KakaoOAuthService.KakaoUserInfo noAccountUserInfo = new KakaoOAuthService.KakaoUserInfo(123456789L, null);
            doReturn("kakao-access-token").when(kakaoOAuthService).fetchAccessToken(anyString(), anyString());
            doReturn(noAccountUserInfo).when(kakaoOAuthService).fetchUserInfo("kakao-access-token");
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(1L)
                    .nickname("카카오 사용자")
                    .provider(AuthProvider.KAKAO)
                    .providerId("123456789")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123456789"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(spyUser));
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = kakaoOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isNull();
        }

        @Test
        @DisplayName("이메일이 없는 신규 사용자는 null 이메일로 저장되고 로그인에 성공한다")
        void login_newUser_emailNull_savesWithNullEmail() {
            KakaoOAuthService.KakaoUserInfo noEmailUserInfo = new KakaoOAuthService.KakaoUserInfo(
                    123456789L,
                    new KakaoOAuthService.KakaoAccount(null, null, null, new KakaoOAuthService.KakaoProfile("홍길동")));
            doReturn("kakao-access-token").when(kakaoOAuthService).fetchAccessToken(anyString(), anyString());
            doReturn(noEmailUserInfo).when(kakaoOAuthService).fetchUserInfo("kakao-access-token");
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(1L)
                    .nickname("홍길동")
                    .provider(AuthProvider.KAKAO)
                    .providerId("123456789")
                    .build());
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123456789"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(spyUser));
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = kakaoOAuthService.login(validRequest());

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isNull();
        }

        @Test
        @DisplayName("이미 다른 방식으로 가입된 이메일이면 SOCIAL_LOGIN_EMAIL_CONFLICT 예외가 발생한다")
        void login_emailConflict_throwsSocialLoginEmailConflict() {
            stubOAuthCalls();
            when(userAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123456789"))
                    .thenReturn(Optional.empty());
            when(userAccountRepository.findByEmail("user@kakao.com"))
                    .thenReturn(Optional.of(UserAccount.builder()
                            .id(99L)
                            .email("user@kakao.com")
                            .provider(AuthProvider.LOCAL)
                            .build()));

            assertThatThrownBy(() -> kakaoOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.SOCIAL_LOGIN_EMAIL_CONFLICT));
        }

        @Test
        @DisplayName("허용되지 않은 redirectUri면 INVALID_REDIRECT_URI 예외가 발생한다")
        void login_invalidRedirectUri_throwsInvalidRedirectUri() {
            assertThatThrownBy(() ->
                            kakaoOAuthService.login(new SocialLoginRequest("auth-code", "http://evil.com/callback")))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_REDIRECT_URI));
        }

        @Test
        @DisplayName("유효하지 않은 인가코드면 INVALID_CREDENTIALS 예외가 발생한다")
        void login_invalidCode_throwsInvalidCredentials() {
            doThrow(new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS))
                    .when(kakaoOAuthService)
                    .fetchAccessToken(anyString(), anyString());

            assertThatThrownBy(() -> kakaoOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CREDENTIALS));
        }

        @Test
        @DisplayName("Kakao 서버 오류(5xx)면 SOCIAL_LOGIN_UNAVAILABLE 예외가 발생한다")
        void login_kakaoServerError_throwsSocialLoginUnavailable() {
            doThrow(new GeneralException(
                            GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE,
                            new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)))
                    .when(kakaoOAuthService)
                    .fetchAccessToken(anyString(), anyString());

            assertThatThrownBy(() -> kakaoOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE));
        }

        @Test
        @DisplayName("Kakao 응답 타임아웃이면 SOCIAL_LOGIN_UNAVAILABLE 예외가 발생한다")
        void login_kakaoTimeout_throwsSocialLoginUnavailable() {
            doThrow(new GeneralException(
                            GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE, new ResourceAccessException("timeout")))
                    .when(kakaoOAuthService)
                    .fetchAccessToken(anyString(), anyString());

            assertThatThrownBy(() -> kakaoOAuthService.login(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE));
        }
    }
}
