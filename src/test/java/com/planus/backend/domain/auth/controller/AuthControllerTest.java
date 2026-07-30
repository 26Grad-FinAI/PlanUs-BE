package com.planus.backend.domain.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    private MockMvc mockMvc;
    private AuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .build();
    }

    private SignUpRequest validRequest() {
        return new SignUpRequest("user@example.com", "pass1234", "pass1234", "닉네임", true);
    }

    @Nested
    @DisplayName("POST /api/auth/signup 성공")
    class Success {

        @Test
        @DisplayName("올바른 요청 시 201과 응답 데이터를 반환한다")
        void signUp_success_returns201() throws Exception {
            SignUpResponse response = new SignUpResponse(1L, "user@example.com", "access-token", "refresh-token", false);
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
    }
}
