package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.auth.dto.LoginRequest;
import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import java.util.Optional;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private UserAccountRepository userAccountRepository;
    private PasswordEncoder passwordEncoder;
    private JwtProvider jwtProvider;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtProvider = mock(JwtProvider.class);
        authService = new AuthService(userAccountRepository, passwordEncoder, jwtProvider);
    }

    private SignUpRequest validRequest() {
        return new SignUpRequest("user@example.com", "pass1234", "pass1234", "닉네임", true);
    }

    private UserAccount savedUser() {
        return UserAccount.builder()
                .id(1L)
                .email("user@example.com")
                .nickname("닉네임")
                .password("encoded")
                .build();
    }

    @Nested
    @DisplayName("회원가입 성공")
    class Success {

        @Test
        @DisplayName("올바른 요청 시 userId, email, 토큰이 담긴 응답을 반환하고 리프레시 토큰 해시가 저장된다")
        void signUp_success() {
            UserAccount spyUser = spy(savedUser());
            when(userAccountRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("encoded");
            when(userAccountRepository.save(any())).thenReturn(spyUser);
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            SignUpResponse response = authService.signUp(validRequest());

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("user@example.com");
            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(response.refreshToken()).isEqualTo("refresh-token");
            assertThat(response.profileCompleted()).isFalse();
            verify(spyUser).updateRefreshToken("hashed-token");
        }
    }

    @Nested
    @DisplayName("회원가입 실패")
    class Failure {

        @Test
        @DisplayName("약관에 동의하지 않으면 TERMS_NOT_AGREED 예외가 발생한다")
        void signUp_termsNotAgreed() {
            SignUpRequest request = new SignUpRequest("user@example.com", "pass1234", "pass1234", "닉네임", false);

            assertThatThrownBy(() -> authService.signUp(request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.TERMS_NOT_AGREED));

            verify(userAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("이메일 형식이 올바르지 않으면 INVALID_EMAIL_FORMAT 예외가 발생한다")
        void signUp_invalidEmailFormat() {
            SignUpRequest request = new SignUpRequest("not-an-email", "pass1234", "pass1234", "닉네임", true);

            assertThatThrownBy(() -> authService.signUp(request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_EMAIL_FORMAT));

            verify(userAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 사용 중인 이메일이면 EMAIL_DUPLICATE 예외가 발생한다")
        void signUp_emailDuplicate() {
            when(userAccountRepository.existsByEmail("user@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.signUp(validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.EMAIL_DUPLICATE));

            verify(userAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("비밀번호가 조건을 충족하지 않으면 PASSWORD_TOO_WEAK 예외가 발생한다")
        void signUp_passwordTooWeak() {
            SignUpRequest request = new SignUpRequest("user@example.com", "weakpw", "weakpw", "닉네임", true);
            when(userAccountRepository.existsByEmail(anyString())).thenReturn(false);

            assertThatThrownBy(() -> authService.signUp(request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.PASSWORD_TOO_WEAK));

            verify(userAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("비밀번호와 확인 비밀번호가 다르면 PASSWORD_MISMATCH 예외가 발생한다")
        void signUp_passwordMismatch() {
            SignUpRequest request = new SignUpRequest("user@example.com", "pass1234", "pass5678", "닉네임", true);
            when(userAccountRepository.existsByEmail(anyString())).thenReturn(false);

            assertThatThrownBy(() -> authService.signUp(request))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.PASSWORD_MISMATCH));

            verify(userAccountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("로그인 성공")
    class LoginSuccess {

        @Test
        @DisplayName("올바른 이메일/비밀번호로 userId, email, 토큰이 담긴 응답을 반환하고 리프레시 토큰 해시가 저장된다")
        void login_success() {
            UserAccount spyUser = spy(savedUser());
            when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.of(spyUser));
            when(passwordEncoder.matches("pass1234", "encoded")).thenReturn(true);
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
            when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-token");

            LoginResponse response = authService.login(new LoginRequest("user@example.com", "pass1234"));

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("user@example.com");
            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(response.refreshToken()).isEqualTo("refresh-token");
            verify(spyUser).updateRefreshToken("hashed-token");
        }
    }

    @Nested
    @DisplayName("로그인 실패")
    class LoginFailure {

        @Test
        @DisplayName("존재하지 않는 이메일이면 INVALID_CREDENTIALS 예외가 발생한다")
        void login_emailNotFound() {
            when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "pass1234")))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CREDENTIALS));
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 INVALID_CREDENTIALS 예외가 발생한다")
        void login_wrongPassword() {
            UserAccount user = savedUser();
            when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrongpass", "encoded")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrongpass")))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_CREDENTIALS));
        }
    }
}
