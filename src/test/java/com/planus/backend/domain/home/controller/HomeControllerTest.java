package com.planus.backend.domain.home.controller;

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
import com.planus.backend.domain.home.dto.HomeCalendarResponse;
import com.planus.backend.domain.home.dto.HomeCalendarResponse.DailySummary;
import com.planus.backend.domain.home.dto.HomeDailyResponse;
import com.planus.backend.domain.home.dto.HomeDailyResponse.TransactionDetail;
import com.planus.backend.domain.home.dto.HomeSummaryResponse;
import com.planus.backend.domain.home.service.HomeService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
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

class HomeControllerTest {

    private MockMvc mockMvc;
    private HomeService homeService;

    @BeforeEach
    void setUp() {
        homeService = mock(HomeService.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(new HomeController(homeService))
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

    @Nested
    @DisplayName("GET /api/home/calendar")
    class GetCalendar {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("올바른 요청 시 200과 날짜별 합계를 반환한다")
            void getCalendar_success_returns200() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                HomeCalendarResponse response = new HomeCalendarResponse(
                        "2026-08",
                        List.of(
                                new DailySummary(LocalDate.of(2026, 8, 1), 45_000L, 0L),
                                new DailySummary(LocalDate.of(2026, 8, 2), 12_000L, 3_000_000L)));
                when(homeService.getCalendar(eq(1L), eq(YearMonth.of(2026, 8)))).thenReturn(response);

                mockMvc.perform(get("/api/home/calendar").param("yearMonth", "2026-08"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.isSuccess").value(true))
                        .andExpect(jsonPath("$.result.yearMonth").value("2026-08"))
                        .andExpect(jsonPath("$.result.dailySummaries").isArray())
                        .andExpect(jsonPath("$.result.dailySummaries.length()").value(2))
                        .andExpect(jsonPath("$.result.dailySummaries[0].date").value("2026-08-01"))
                        .andExpect(jsonPath("$.result.dailySummaries[0].totalExpense")
                                .value(45000))
                        .andExpect(jsonPath("$.result.dailySummaries[0].totalIncome")
                                .value(0))
                        .andExpect(jsonPath("$.result.dailySummaries[1].totalExpense")
                                .value(12000))
                        .andExpect(jsonPath("$.result.dailySummaries[1].totalIncome")
                                .value(3000000));

                verify(homeService).getCalendar(eq(1L), eq(YearMonth.of(2026, 8)));
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("yearMonth 파라미터가 없으면 400을 반환한다")
            void getCalendar_missingYearMonth_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                mockMvc.perform(get("/api/home/calendar")).andExpect(status().isBadRequest());
            }
        }
    }

    @Nested
    @DisplayName("GET /api/home/daily")
    class GetDailyDetail {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("올바른 요청 시 200과 거래 상세 내역을 반환한다")
            void getDailyDetail_success_returns200() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                HomeDailyResponse response = new HomeDailyResponse(
                        LocalDate.of(2026, 8, 22),
                        15_000L,
                        3_000_000L,
                        List.of(
                                new TransactionDetail(
                                        1L,
                                        "EXPENSE",
                                        15_000L,
                                        "점심 식사",
                                        1,
                                        null,
                                        "NECESSARY",
                                        false,
                                        false,
                                        LocalTime.of(12, 30)),
                                new TransactionDetail(
                                        2L,
                                        "INCOME",
                                        3_000_000L,
                                        "월급",
                                        1,
                                        null,
                                        null,
                                        true,
                                        true,
                                        LocalTime.of(9, 0))));
                when(homeService.getDailyDetail(eq(1L), eq(LocalDate.of(2026, 8, 22))))
                        .thenReturn(response);

                mockMvc.perform(get("/api/home/daily").param("date", "2026-08-22"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.isSuccess").value(true))
                        .andExpect(jsonPath("$.result.date").value("2026-08-22"))
                        .andExpect(jsonPath("$.result.totalExpense").value(15000))
                        .andExpect(jsonPath("$.result.totalIncome").value(3000000))
                        .andExpect(jsonPath("$.result.transactions.length()").value(2))
                        .andExpect(jsonPath("$.result.transactions[0].id").value(1))
                        .andExpect(jsonPath("$.result.transactions[0].type").value("EXPENSE"))
                        .andExpect(jsonPath("$.result.transactions[0].title").value("점심 식사"))
                        .andExpect(jsonPath("$.result.transactions[1].type").value("INCOME"));

                verify(homeService).getDailyDetail(eq(1L), eq(LocalDate.of(2026, 8, 22)));
            }

            @Test
            @DisplayName("거래가 없는 날짜도 200과 빈 목록을 반환한다")
            void getDailyDetail_noTransactions_returns200WithEmptyList() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                HomeDailyResponse response =
                        new HomeDailyResponse(LocalDate.of(2026, 8, 22), 0L, 0L, Collections.emptyList());
                when(homeService.getDailyDetail(eq(1L), eq(LocalDate.of(2026, 8, 22))))
                        .thenReturn(response);

                mockMvc.perform(get("/api/home/daily").param("date", "2026-08-22"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.result.totalExpense").value(0))
                        .andExpect(jsonPath("$.result.totalIncome").value(0))
                        .andExpect(jsonPath("$.result.transactions").isEmpty());
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("date 파라미터가 없으면 400을 반환한다")
            void getDailyDetail_missingDate_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                mockMvc.perform(get("/api/home/daily")).andExpect(status().isBadRequest());
            }
        }
    }
}
