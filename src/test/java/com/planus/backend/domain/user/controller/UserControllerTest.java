package com.planus.backend.domain.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.service.UserService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import org.junit.jupiter.api.AfterEach;
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

class UserControllerTest {

    private MockMvc mockMvc;
    private AuthService authService;
    private UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        userService = mock(UserService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(authService, userService))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("POST /api/users/me/profile")
    class SaveProfile {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("올바른 요청 시 201과 userId·nickname·availableBudget을 반환한다")
            void saveProfile_success_returns201() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                UserProfileResponse response =
                        new UserProfileResponse(1L, "김스펜드", 1_500_000L, true, "프로필 저장 완료. AI 예산 산출이 시작됩니다.");
                when(userService.saveProfile(eq(1L), any())).thenReturn(response);

                mockMvc.perform(post("/api/users/me/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.isSuccess").value(true))
                        .andExpect(jsonPath("$.result.userId").value(1L))
                        .andExpect(jsonPath("$.result.nickname").value("김스펜드"))
                        .andExpect(jsonPath("$.result.availableBudget").value(1_500_000L))
                        .andExpect(jsonPath("$.result.profileCompleted").value(true))
                        .andExpect(jsonPath("$.result.message").value("프로필 저장 완료. AI 예산 산출이 시작됩니다."));

                verify(userService).saveProfile(eq(1L), any());
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("필수 필드 누락 시 400을 반환한다")
            void saveProfile_missingField_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                mockMvc.perform(post("/api/users/me/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }

            @Test
            @DisplayName("존재하지 않는 userId이면 404를 반환한다")
            void saveProfile_userNotFound_returns404() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(999L, null));
                when(userService.saveProfile(eq(999L), any()))
                        .thenThrow(new GeneralException(GeneralErrorCode.NOT_FOUND));

                mockMvc.perform(post("/api/users/me/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_404_001"));
            }
        }
    }

    @Nested
    @DisplayName("DELETE /api/users/me")
    class Withdraw {

        @Test
        @DisplayName("회원 탈퇴 성공 시 200 OK를 반환한다")
        void withdraw_success() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(1L, null));

            mockMvc.perform(delete("/api/users/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true));

            verify(authService).withdraw(1L);
        }

        @Test
        @DisplayName("존재하지 않는 유저 탈퇴 시도 시 404 NOT_FOUND를 반환한다")
        void withdraw_userNotFound_returns404() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(999L, null));
            doThrow(new GeneralException(GeneralErrorCode.NOT_FOUND))
                    .when(authService).withdraw(999L);

            mockMvc.perform(delete("/api/users/me"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.isSuccess").value(false));
        }
    }

    private UserProfileRequest validRequest() {
        return new UserProfileRequest(
                30,
                "MALE",
                "서울",
                3_000_000L,
                1_000_000L,
                500_000L,
                "독서",
                "SAVING",
                true,
                false);
    }
}
