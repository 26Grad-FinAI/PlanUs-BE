package com.planus.backend.domain.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.auth.dto.LoginRequest;
import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.ReissueRequest;
import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import com.planus.backend.domain.auth.dto.SocialLoginRequest;
import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.domain.auth.service.GoogleOAuthService;
import com.planus.backend.domain.auth.service.KakaoOAuthService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    private MockMvc mockMvc;
    private AuthService authService;
    private GoogleOAuthService googleOAuthService;
    private KakaoOAuthService kakaoOAuthService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        googleOAuthService = mock(GoogleOAuthService.class);
        kakaoOAuthService = mock(KakaoOAuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService, googleOAuthService, kakaoOAuthService))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private SignUpRequest validRequest() {
        return new SignUpRequest("user@example.com", "pass1234", "pass1234", true);
    }

    @Nested
    @DisplayName("POST /api/auth/signup 성공")
    class Success {

        @Test
        @DisplayName("올바른 요청 시 201과 응답 데이터를 반환한다")
        void signUp_success_returns201() throws Exception {
            SignUpResponse response =
                    new SignUpResponse(1L, "user@example.com", "access-token", "refresh-token", false);
            when(authService.signUp(any())).thenReturn(response);

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.userId").value(1L))
                    .andExpect(jsonPath("$.result.email").value("user@example.com"))
                    .andExpect(jsonPath("$.result.accessToken").value("access-token"))
                    .andExpect(jsonPath("$.result.refreshToken").value("refresh-token"))
                    .andExpect(jsonPath("$.result.profileCompleted").value(false));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/signup 실패")
    class Failure {

        @Test
        @DisplayName("필수 필드 누락 시 400을 반환한다")
        void signUp_missingField_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("COMMON_400_002"));
        }

        @Test
        @DisplayName("이메일 중복 시 409를 반환한다")
        void signUp_emailDuplicate_returns409() throws Exception {
            when(authService.signUp(any())).thenThrow(new GeneralException(GeneralErrorCode.EMAIL_DUPLICATE));

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_409_001"));
        }

        @Test
        @DisplayName("이메일 형식 오류 시 400을 반환한다")
        void signUp_invalidEmail_returns400() throws Exception {
            when(authService.signUp(any())).thenThrow(new GeneralException(GeneralErrorCode.INVALID_EMAIL_FORMAT));

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_400_001"));
        }

        @Test
        @DisplayName("약관 미동의 시 400을 반환한다")
        void signUp_termsNotAgreed_returns400() throws Exception {
            when(authService.signUp(any())).thenThrow(new GeneralException(GeneralErrorCode.TERMS_NOT_AGREED));

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_400_004"));
        }

        @Test
        @DisplayName("비밀번호 강도 미달 시 400을 반환한다")
        void signUp_passwordTooWeak_returns400() throws Exception {
            when(authService.signUp(any())).thenThrow(new GeneralException(GeneralErrorCode.PASSWORD_TOO_WEAK));

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_400_003"));
        }

        @Test
        @DisplayName("비밀번호 불일치 시 400을 반환한다")
        void signUp_passwordMismatch_returns400() throws Exception {
            when(authService.signUp(any())).thenThrow(new GeneralException(GeneralErrorCode.PASSWORD_MISMATCH));

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_400_002"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login 성공")
    class LoginSuccess {

        @Test
        @DisplayName("올바른 이메일/비밀번호로 200과 토큰을 반환한다")
        void login_success_returns200() throws Exception {
            LoginResponse response = new LoginResponse(1L, "user@example.com", "access-token", "refresh-token", false);
            when(authService.login(any())).thenReturn(response);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest("user@example.com", "pass1234"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.userId").value(1L))
                    .andExpect(jsonPath("$.result.accessToken").value("access-token"))
                    .andExpect(jsonPath("$.result.refreshToken").value("refresh-token"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login 실패")
    class LoginFailure {

        @Test
        @DisplayName("필수 필드 누락 시 400을 반환한다")
        void login_missingField_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_400_002"));
        }

        @Test
        @DisplayName("이메일 또는 비밀번호가 올바르지 않으면 401을 반환한다")
        void login_invalidCredentials_returns401() throws Exception {
            when(authService.login(any())).thenThrow(new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    objectMapper.writeValueAsString(new LoginRequest("user@example.com", "wrongpass"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_401_002"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/oauth2/google 성공")
    class GoogleLoginSuccess {

        @Test
        @DisplayName("올바른 인가코드로 200과 토큰을 반환한다")
        void googleLogin_success_returns200() throws Exception {
            LoginResponse response = new LoginResponse(1L, "user@example.com", "access-token", "refresh-token", false);
            when(googleOAuthService.login(any())).thenReturn(response);

            mockMvc.perform(post("/api/auth/oauth2/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SocialLoginRequest("auth-code", "http://localhost/callback"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.userId").value(1L))
                    .andExpect(jsonPath("$.result.accessToken").value("access-token"))
                    .andExpect(jsonPath("$.result.refreshToken").value("refresh-token"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/oauth2/google 실패")
    class GoogleLoginFailure {

        @Test
        @DisplayName("필수 필드 누락 시 400을 반환한다")
        void googleLogin_missingField_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/oauth2/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_400_002"));
        }

        @Test
        @DisplayName("유효하지 않은 인가코드면 401을 반환한다")
        void googleLogin_invalidCode_returns401() throws Exception {
            when(googleOAuthService.login(any())).thenThrow(new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS));

            mockMvc.perform(post("/api/auth/oauth2/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SocialLoginRequest("bad-code", "http://localhost/callback"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_401_002"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/oauth2/kakao 성공")
    class KakaoLoginSuccess {

        @Test
        @DisplayName("올바른 인가코드로 200과 토큰을 반환한다")
        void kakaoLogin_success_returns200() throws Exception {
            LoginResponse response = new LoginResponse(1L, "user@kakao.com", "access-token", "refresh-token", false);
            when(kakaoOAuthService.login(any())).thenReturn(response);

            mockMvc.perform(post("/api/auth/oauth2/kakao")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SocialLoginRequest("auth-code", "http://localhost/callback"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.userId").value(1L))
                    .andExpect(jsonPath("$.result.accessToken").value("access-token"))
                    .andExpect(jsonPath("$.result.refreshToken").value("refresh-token"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/oauth2/kakao 실패")
    class KakaoLoginFailure {

        @Test
        @DisplayName("필수 필드 누락 시 400을 반환한다")
        void kakaoLogin_missingField_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/oauth2/kakao")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_400_002"));
        }

        @Test
        @DisplayName("유효하지 않은 인가코드면 401을 반환한다")
        void kakaoLogin_invalidCode_returns401() throws Exception {
            when(kakaoOAuthService.login(any())).thenThrow(new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS));

            mockMvc.perform(post("/api/auth/oauth2/kakao")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SocialLoginRequest("bad-code", "http://localhost/callback"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_401_002"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/reissue 성공")
    class ReissueSuccess {

        @Test
        @DisplayName("유효한 리프레시 토큰으로 200과 새 토큰을 반환한다")
        void reissue_success_returns200() throws Exception {
            LoginResponse response =
                    new LoginResponse(1L, "user@example.com", "new-access-token", "new-refresh-token", false);
            when(authService.reissue(any())).thenReturn(response);

            mockMvc.perform(post("/api/auth/reissue")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ReissueRequest("valid-refresh-token"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.accessToken").value("new-access-token"))
                    .andExpect(jsonPath("$.result.refreshToken").value("new-refresh-token"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/logout 성공")
    class LogoutSuccess {

        @Test
        @DisplayName("유효한 액세스 토큰으로 200을 반환한다")
        void logout_success_returns200() throws Exception {
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(1L, null, List.of()));
            try {
                mockMvc.perform(post("/api/auth/logout"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.isSuccess").value(true));
                verify(authService).logout(1L);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Nested
    @DisplayName("POST /api/auth/reissue 실패")
    class ReissueFailure {

        @Test
        @DisplayName("필수 필드 누락 시 400을 반환한다")
        void reissue_missingField_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/reissue")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_400_002"));
        }

        @Test
        @DisplayName("만료된 리프레시 토큰이면 401을 반환한다")
        void reissue_expiredToken_returns401() throws Exception {
            when(authService.reissue(any())).thenThrow(new GeneralException(GeneralErrorCode.EXPIRED_TOKEN));

            mockMvc.perform(post("/api/auth/reissue")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ReissueRequest("expired-token"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_401_004"));
        }

        @Test
        @DisplayName("유효하지 않은 리프레시 토큰이면 401을 반환한다")
        void reissue_invalidToken_returns401() throws Exception {
            when(authService.reissue(any())).thenThrow(new GeneralException(GeneralErrorCode.INVALID_TOKEN));

            mockMvc.perform(post("/api/auth/reissue")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ReissueRequest("invalid-token"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.code").value("AUTH_401_003"));
        }
    }
}
