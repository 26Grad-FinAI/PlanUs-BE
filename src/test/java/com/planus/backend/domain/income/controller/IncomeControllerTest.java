package com.planus.backend.domain.income.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.planus.backend.domain.income.dto.IncomeRequest;
import com.planus.backend.domain.income.dto.IncomeResponse;
import com.planus.backend.domain.income.service.IncomeService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import java.time.LocalDateTime;
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

class IncomeControllerTest {

    private MockMvc mockMvc;
    private IncomeService incomeService;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        incomeService = mock(IncomeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new IncomeController(incomeService))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("POST /api/incomes")
    class CreateIncome {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("올바른 요청 시 201과 등록된 수입 정보를 반환한다")
            void createIncome_success_returns201() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                IncomeResponse response = new IncomeResponse(
                        1L, 500000L, "월급",
                        LocalDateTime.of(2026, 8, 25, 9, 0),
                        1, "8월 급여",
                        LocalDateTime.of(2026, 8, 25, 9, 0));
                when(incomeService.createIncome(eq(1L), any())).thenReturn(response);

                mockMvc.perform(post("/api/incomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.isSuccess").value(true))
                        .andExpect(jsonPath("$.result.id").value(1L))
                        .andExpect(jsonPath("$.result.amount").value(500000L))
                        .andExpect(jsonPath("$.result.title").value("월급"))
                        .andExpect(jsonPath("$.result.categoryId").value(1))
                        .andExpect(jsonPath("$.result.memo").value("8월 급여"));

                verify(incomeService).createIncome(eq(1L), any());
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("필수 필드 누락 시 400을 반환한다")
            void createIncome_missingField_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                mockMvc.perform(post("/api/incomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }

            @Test
            @DisplayName("금액이 0이면 400을 반환한다")
            void createIncome_zeroAmount_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                IncomeRequest request = new IncomeRequest(
                        0L, "테스트", LocalDateTime.of(2026, 8, 25, 9, 0), 1, null);

                mockMvc.perform(post("/api/incomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }

            @Test
            @DisplayName("내역이 빈 문자열이면 400을 반환한다")
            void createIncome_blankTitle_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                IncomeRequest request = new IncomeRequest(
                        100000L, "  ", LocalDateTime.of(2026, 8, 25, 9, 0), 1, null);

                mockMvc.perform(post("/api/incomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }

            @Test
            @DisplayName("유효하지 않은 카테고리이면 400을 반환한다")
            void createIncome_invalidCategory_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                when(incomeService.createIncome(eq(1L), any()))
                        .thenThrow(new GeneralException(GeneralErrorCode.INVALID_CATEGORY));

                IncomeRequest request = new IncomeRequest(
                        100000L, "테스트", LocalDateTime.of(2026, 8, 25, 9, 0), 99, null);

                mockMvc.perform(post("/api/incomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("EXPENSE_400_001"));
            }

            @Test
            @DisplayName("해당 월 예산 미설정 시 404를 반환한다")
            void createIncome_budgetNotFound_returns404() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                when(incomeService.createIncome(eq(1L), any()))
                        .thenThrow(new GeneralException(GeneralErrorCode.BUDGET_NOT_FOUND));

                mockMvc.perform(post("/api/incomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("INCOME_404_001"));
            }

            @Test
            @DisplayName("카테고리 예산 배분 미설정 시 404를 반환한다")
            void createIncome_budgetCategoryNotFound_returns404() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                when(incomeService.createIncome(eq(1L), any()))
                        .thenThrow(new GeneralException(GeneralErrorCode.BUDGET_CATEGORY_NOT_FOUND));

                mockMvc.perform(post("/api/incomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("INCOME_404_002"));
            }

            @Test
            @DisplayName("예산 오버플로 시 400을 반환한다")
            void createIncome_budgetOverflow_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                when(incomeService.createIncome(eq(1L), any()))
                        .thenThrow(new GeneralException(GeneralErrorCode.BUDGET_OVERFLOW));

                mockMvc.perform(post("/api/incomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("INCOME_400_001"));
            }

            @Test
            @DisplayName("내역이 255자를 초과하면 400을 반환한다")
            void createIncome_titleTooLong_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                IncomeRequest request = new IncomeRequest(
                        100000L, "가".repeat(256),
                        LocalDateTime.of(2026, 8, 25, 9, 0), 1, null);

                mockMvc.perform(post("/api/incomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }
        }
    }

    private IncomeRequest validRequest() {
        return new IncomeRequest(
                500000L,
                "월급",
                LocalDateTime.of(2026, 8, 25, 9, 0),
                1,
                "8월 급여");
    }
}
