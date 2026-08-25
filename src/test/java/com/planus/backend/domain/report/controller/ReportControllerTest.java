package com.planus.backend.domain.report.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.planus.backend.domain.report.dto.MonthlyReportResponse;
import com.planus.backend.domain.report.dto.MonthlyReportResponse.CategoryBudgetStatus;
import com.planus.backend.domain.report.dto.MonthlyReportResponse.CategoryRatio;
import com.planus.backend.domain.report.dto.MonthlyReportResponse.MonthlyTrend;
import com.planus.backend.domain.report.service.ReportService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import java.util.List;
import java.util.Collections;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReportControllerTest {

    private MockMvc mockMvc;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(new ReportController(reportService))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(converter)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("GET /api/report/monthly")
    class GetMonthlyReport {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("올바른 요청 시 200과 월간 리포트를 반환한다")
            void getMonthlyReport_success_returns200() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                MonthlyReportResponse response = new MonthlyReportResponse(
                        "2026-08",
                        520_000L,
                        800_000L,
                        List.of(
                                new CategoryRatio(1, "식료품", 300_000L, 57.7),
                                new CategoryRatio(10, "여행·숙박", 220_000L, 42.3)),
                        List.of(
                                new MonthlyTrend("2026-03", 0L),
                                new MonthlyTrend("2026-04", 430_000L),
                                new MonthlyTrend("2026-05", 480_000L),
                                new MonthlyTrend("2026-06", 300_000L),
                                new MonthlyTrend("2026-07", 490_000L),
                                new MonthlyTrend("2026-08", 520_000L)),
                        List.of(
                                new CategoryBudgetStatus(1, "식료품", 400_000L, 300_000L),
                                new CategoryBudgetStatus(10, "여행·숙박", 300_000L, 220_000L)));

                when(reportService.getMonthlyReport(eq(1L), eq(YearMonth.of(2026, 8))))
                        .thenReturn(response);

                mockMvc.perform(get("/api/report/monthly").param("yearMonth", "2026-08"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.isSuccess").value(true))
                        .andExpect(jsonPath("$.result.yearMonth").value("2026-08"))
                        .andExpect(jsonPath("$.result.totalExpense").value(520000))
                        .andExpect(jsonPath("$.result.totalBudget").value(800000))
                        .andExpect(jsonPath("$.result.categoryRatios").isArray())
                        .andExpect(jsonPath("$.result.categoryRatios.length()").value(2))
                        .andExpect(jsonPath("$.result.categoryRatios[0].categoryId").value(1))
                        .andExpect(jsonPath("$.result.categoryRatios[0].categoryName").value("식료품"))
                        .andExpect(jsonPath("$.result.categoryRatios[0].amount").value(300000))
                        .andExpect(jsonPath("$.result.categoryRatios[0].ratio").value(57.7))
                        .andExpect(jsonPath("$.result.monthlyTrends").isArray())
                        .andExpect(jsonPath("$.result.monthlyTrends.length()").value(6))
                        .andExpect(jsonPath("$.result.monthlyTrends[0].yearMonth").value("2026-03"))
                        .andExpect(jsonPath("$.result.monthlyTrends[0].totalExpense").value(0))
                        .andExpect(jsonPath("$.result.monthlyTrends[5].yearMonth").value("2026-08"))
                        .andExpect(jsonPath("$.result.monthlyTrends[5].totalExpense").value(520000))
                        .andExpect(jsonPath("$.result.categoryBudgets").isArray())
                        .andExpect(jsonPath("$.result.categoryBudgets.length()").value(2))
                        .andExpect(jsonPath("$.result.categoryBudgets[0].categoryId").value(1))
                        .andExpect(jsonPath("$.result.categoryBudgets[0].budget").value(400000))
                        .andExpect(jsonPath("$.result.categoryBudgets[0].expense").value(300000));

                verify(reportService).getMonthlyReport(eq(1L), eq(YearMonth.of(2026, 8)));
            }

            @Test
            @DisplayName("지출과 예산이 없어도 200과 빈 목록을 반환한다")
            void getMonthlyReport_noData_returns200WithEmptyLists() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                MonthlyReportResponse response = new MonthlyReportResponse(
                        "2026-08",
                        0L,
                        2_000_000L,
                        Collections.emptyList(),
                        List.of(
                                new MonthlyTrend("2026-03", 0L),
                                new MonthlyTrend("2026-04", 0L),
                                new MonthlyTrend("2026-05", 0L),
                                new MonthlyTrend("2026-06", 0L),
                                new MonthlyTrend("2026-07", 0L),
                                new MonthlyTrend("2026-08", 0L)),
                        Collections.emptyList());

                when(reportService.getMonthlyReport(eq(1L), eq(YearMonth.of(2026, 8))))
                        .thenReturn(response);

                mockMvc.perform(get("/api/report/monthly").param("yearMonth", "2026-08"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.result.totalExpense").value(0))
                        .andExpect(jsonPath("$.result.categoryRatios").isEmpty())
                        .andExpect(jsonPath("$.result.monthlyTrends.length()").value(6))
                        .andExpect(jsonPath("$.result.categoryBudgets").isEmpty());
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("yearMonth 파라미터가 없으면 400을 반환한다")
            void getMonthlyReport_missingYearMonth_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                mockMvc.perform(get("/api/report/monthly"))
                        .andExpect(status().isBadRequest());
            }

            @Test
            @DisplayName("yearMonth 형식이 잘못되면 400을 반환한다")
            void getMonthlyReport_invalidYearMonthFormat_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                mockMvc.perform(get("/api/report/monthly").param("yearMonth", "2026-08-01"))
                        .andExpect(status().isBadRequest());
            }

            @Test
            @DisplayName("사용자를 찾을 수 없으면 404를 반환한다")
            void getMonthlyReport_userNotFound_returns404() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(1L, null));

                when(reportService.getMonthlyReport(eq(1L), eq(YearMonth.of(2026, 8))))
                        .thenThrow(new GeneralException(GeneralErrorCode.NOT_FOUND));

                mockMvc.perform(get("/api/report/monthly").param("yearMonth", "2026-08"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_404_001"));
            }
        }
    }
}
