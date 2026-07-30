package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
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
        @DisplayName("올바른 요청 시 userId, email, 토큰이 담긴 응답을 반환한다")
        void signUp_success() {
            when(userAccountRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("encoded");
            when(userAccountRepository.save(any())).thenReturn(savedUser());
            when(jwtProvider.generateAccessToken(1L)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");

            SignUpResponse response = authService.signUp(validRequest());

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("user@example.com");
            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(response.refreshToken()).isEqualTo("refresh-token");
            assertThat(response.profileCompleted()).isFalse();
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
}
