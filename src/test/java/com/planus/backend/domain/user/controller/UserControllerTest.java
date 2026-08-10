package com.planus.backend.domain.user.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserControllerTest {

    private MockMvc mockMvc;
    private AuthService authService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(authService))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

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
