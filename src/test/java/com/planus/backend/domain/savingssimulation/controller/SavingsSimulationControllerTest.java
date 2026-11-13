package com.planus.backend.domain.savingssimulation.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.savingssimulation.dto.*;
import com.planus.backend.domain.savingssimulation.dto.SimulateResponse.DisplayType;
import com.planus.backend.domain.savingssimulation.service.SavingsSimulationService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import java.util.List;
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

class SavingsSimulationControllerTest {

    private MockMvc mockMvc;
    private SavingsSimulationService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(SavingsSimulationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SavingsSimulationController(service))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId, null));
    }

    // ── suggest ──

    @Nested
    @DisplayName("GET /api/savings-simulation/suggest")
    class SuggestEndpoint {

        @Test
        @DisplayName("정상 조회 시 200과 제안 목록 반환")
        void success() throws Exception {
            setAuth(1L);

            SuggestResponse response = new SuggestResponse(
                    List.of(new SuggestResponse.ActionCard(
                            7,
                            "식대",
                            40000,
                            "REDUCE_FREQUENCY",
                            "주 4.2회 외식",
                            "주 2회로 감소",
                            "금요일 저녁 외식이 전체의 60%",
                            0.74,
                            90000,
                            1080000)),
                    40000,
                    90000,
                    1080000);

            when(service.suggest(eq(1L), eq("2026-11"))).thenReturn(response);

            mockMvc.perform(get("/api/savings-simulation/suggest").param("yearMonth", "2026-11"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.totalPossibleReduction").value(40000))
                    .andExpect(jsonPath("$.result.actions").isArray())
                    .andExpect(jsonPath("$.result.actions[0].categoryId").value(7))
                    .andExpect(jsonPath("$.result.actions[0].categoryName").value("식대"))
                    .andExpect(jsonPath("$.result.actions[0].actionType").value("REDUCE_FREQUENCY"))
                    .andExpect(jsonPath("$.result.actions[0].currentBehavior").value("주 4.2회 외식"))
                    .andExpect(jsonPath("$.result.actions[0].targetBehavior").value("주 2회로 감소"))
                    .andExpect(jsonPath("$.result.actions[0].monthlySavingWon").value(90000))
                    .andExpect(jsonPath("$.result.actions[0].annualSavingWon").value(1080000))
                    .andExpect(jsonPath("$.result.totalAnnualSaving").value(1080000));

            verify(service).suggest(1L, "2026-11");
        }

        @Test
        @DisplayName("피드백이 없으면 404 반환")
        void feedbackNotFound() throws Exception {
            setAuth(1L);

            when(service.suggest(eq(1L), eq("2026-11")))
                    .thenThrow(new GeneralException(GeneralErrorCode.FEEDBACK_NOT_FOUND));

            mockMvc.perform(get("/api/savings-simulation/suggest").param("yearMonth", "2026-11"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.isSuccess").value(false));
        }
    }

    // ── simulate ──

    @Nested
    @DisplayName("POST /api/savings-simulation/simulate")
    class SimulateEndpoint {

        @Test
        @DisplayName("정상 시뮬레이션 시 200과 결과 반환")
        void success() throws Exception {
            setAuth(1L);

            SimulateResponse response = new SimulateResponse(
                    DisplayType.DEFICIT,
                    -50000,
                    10000,
                    60000,
                    90000,
                    1080000,
                    "이번 달 50,000원 적자가 절약 시 균형에 도달합니다",
                    List.of(new SuggestResponse.ActionCard(
                            7,
                            "식대",
                            40000,
                            "REDUCE_FREQUENCY",
                            "주 4.2회 외식",
                            "주 2회로 감소",
                            "금요일 저녁 외식이 전체의 60%",
                            0.74,
                            90000,
                            1080000)));

            when(service.simulate(eq(1L), any(SimulateRequest.class))).thenReturn(response);

            SimulateRequest request = new SimulateRequest("2026-11", List.of(7, 5));

            mockMvc.perform(post("/api/savings-simulation/simulate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.displayType").value("DEFICIT"))
                    .andExpect(jsonPath("$.result.currentMonthlySavings").value(-50000))
                    .andExpect(jsonPath("$.result.projectedMonthlySavings").value(10000))
                    .andExpect(jsonPath("$.result.reductionTotal").value(60000))
                    .andExpect(jsonPath("$.result.selectedActions").isArray());

            verify(service).simulate(eq(1L), any(SimulateRequest.class));
        }

        @Test
        @DisplayName("유효하지 않은 카테고리 선택 시 400 반환")
        void invalidCategory() throws Exception {
            setAuth(1L);

            when(service.simulate(eq(1L), any(SimulateRequest.class)))
                    .thenThrow(new GeneralException(GeneralErrorCode.INVALID_CATEGORY_SELECTION));

            SimulateRequest request = new SimulateRequest("2026-11", List.of(99));

            mockMvc.perform(post("/api/savings-simulation/simulate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.isSuccess").value(false));
        }
    }

    // ── apply ──

    @Nested
    @DisplayName("POST /api/savings-simulation/apply")
    class ApplyEndpoint {

        @Test
        @DisplayName("정상 예산 반영 시 200과 결과 반환")
        void success() throws Exception {
            setAuth(1L);

            ApplyResponse response = new ApplyResponse(
                    true, List.of(new ApplyResponse.CategoryChange(7, 200000, 160000)), 1540000, 500000);

            when(service.apply(eq(1L), any(ApplyRequest.class))).thenReturn(response);

            ApplyRequest request = new ApplyRequest("2026-11", List.of(7));

            mockMvc.perform(post("/api/savings-simulation/apply")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.applied").value(true))
                    .andExpect(
                            jsonPath("$.result.updatedCategories[0].categoryId").value(7))
                    .andExpect(jsonPath("$.result.updatedCategories[0].before").value(200000))
                    .andExpect(jsonPath("$.result.updatedCategories[0].after").value(160000))
                    .andExpect(jsonPath("$.result.newTotalBudget").value(1540000))
                    .andExpect(jsonPath("$.result.monthlySavings").value(500000));

            verify(service).apply(eq(1L), any(ApplyRequest.class));
        }

        @Test
        @DisplayName("예산 초과 시 409 반환")
        void budgetExceeded() throws Exception {
            setAuth(1L);

            when(service.apply(eq(1L), any(ApplyRequest.class)))
                    .thenThrow(new GeneralException(GeneralErrorCode.BUDGET_EXCEEDED));

            ApplyRequest request = new ApplyRequest("2026-11", List.of(7));

            mockMvc.perform(post("/api/savings-simulation/apply")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.isSuccess").value(false));
        }

        @Test
        @DisplayName("예산이 없으면 404 반환")
        void budgetNotFound() throws Exception {
            setAuth(1L);

            when(service.apply(eq(1L), any(ApplyRequest.class)))
                    .thenThrow(new GeneralException(GeneralErrorCode.BUDGET_NOT_FOUND));

            ApplyRequest request = new ApplyRequest("2026-11", List.of(7));

            mockMvc.perform(post("/api/savings-simulation/apply")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.isSuccess").value(false));
        }
    }
}
