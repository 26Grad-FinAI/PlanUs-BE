package com.planus.backend.domain.home.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.planus.backend.domain.home.dto.HomeSummaryResponse;
import com.planus.backend.domain.home.service.HomeService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HomeControllerTest {

    private MockMvc mockMvc;
    private HomeService homeService;

    @BeforeEach
    void setUp() {
        homeService = mock(HomeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new HomeController(homeService))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("GET /api/home/summary")
    class GetSummary {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("올바른 요청 시 200과 재정 요약을 반환한다")
            void getSummary_success_returns200() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                HomeSummaryResponse response =
                        new HomeSummaryResponse("2026-08", 850_000L, 2_000_000L, 42.5, 1_150_000L);
                when(homeService.getSummary(eq(1L), eq(YearMonth.of(2026, 8)))).thenReturn(response);

                mockMvc.perform(get("/api/home/summary").param("yearMonth", "2026-08"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.isSuccess").value(true))
                        .andExpect(jsonPath("$.result.yearMonth").value("2026-08"))
                        .andExpect(jsonPath("$.result.totalExpense").value(850000))
                        .andExpect(jsonPath("$.result.budget").value(2000000))
                        .andExpect(jsonPath("$.result.burnRate").value(42.5))
                        .andExpect(jsonPath("$.result.remainingBudget").value(1150000));

                verify(homeService).getSummary(eq(1L), eq(YearMonth.of(2026, 8)));
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("yearMonth 파라미터가 없으면 400을 반환한다")
            void getSummary_missingYearMonth_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                mockMvc.perform(get("/api/home/summary")).andExpect(status().isBadRequest());
            }

            @Test
            @DisplayName("사용자를 찾을 수 없으면 404를 반환한다")
            void getSummary_userNotFound_returns404() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                when(homeService.getSummary(eq(1L), eq(YearMonth.of(2026, 8))))
                        .thenThrow(new GeneralException(GeneralErrorCode.NOT_FOUND));

                mockMvc.perform(get("/api/home/summary").param("yearMonth", "2026-08"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_404_001"));
            }
        }
    }
}
