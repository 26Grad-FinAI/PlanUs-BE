package com.planus.backend.domain.aifeedback.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.planus.backend.domain.aifeedback.dto.WeeklyFeedbackResponse;
import com.planus.backend.domain.aifeedback.service.AiFeedbackQueryService;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiFeedbackControllerTest {

    private MockMvc mockMvc;
    private AiFeedbackQueryService service;

    @BeforeEach
    void setUp() {
        service = mock(AiFeedbackQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AiFeedbackController(service))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                // Spring Boot 기본 설정과 같게 — 날짜를 배열이 아닌 ISO 문자열로 직렬화
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("weekEnd 없이 조회하면 200과 가장 최근 피드백 반환")
    void latest() throws Exception {
        when(service.getWeekly(1L, null))
                .thenReturn(new WeeklyFeedbackResponse(
                        true,
                        LocalDate.of(2026, 11, 9),
                        LocalDate.of(2026, 11, 15),
                        "이번 주는 식대가 많았어요.",
                        "ALERT",
                        "MEDIUM",
                        LocalDateTime.of(2026, 11, 16, 9, 0)));

        mockMvc.perform(get("/api/ai-feedback/weekly/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.exists").value(true))
                .andExpect(jsonPath("$.result.feedbackText").value("이번 주는 식대가 많았어요."))
                .andExpect(jsonPath("$.result.weekEnd").value("2026-11-15"));
    }

    @Test
    @DisplayName("weekEnd를 넘기면 그 날짜로 조회")
    void byWeekEnd() throws Exception {
        when(service.getWeekly(eq(1L), eq(LocalDate.of(2026, 11, 8)))).thenReturn(WeeklyFeedbackResponse.empty());

        mockMvc.perform(get("/api/ai-feedback/weekly/latest").param("weekEnd", "2026-11-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.exists").value(false));

        verify(service).getWeekly(1L, LocalDate.of(2026, 11, 8));
    }

    @Test
    @DisplayName("피드백이 없으면 200과 exists=false")
    void empty() throws Exception {
        when(service.getWeekly(1L, null)).thenReturn(WeeklyFeedbackResponse.empty());

        mockMvc.perform(get("/api/ai-feedback/weekly/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.exists").value(false));
    }

    @Test
    @DisplayName("weekEnd 형식이 잘못되면 400")
    void badDate() throws Exception {
        mockMvc.perform(get("/api/ai-feedback/weekly/latest").param("weekEnd", "2026/11/08"))
                .andExpect(status().isBadRequest());
    }
}
