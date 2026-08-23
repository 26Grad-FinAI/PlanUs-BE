package com.planus.backend.domain.expense.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.planus.backend.domain.expense.dto.ExpenseRequest;
import com.planus.backend.domain.expense.dto.ExpenseResponse;
import com.planus.backend.domain.expense.service.ExpenseService;
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

class ExpenseControllerTest {

    private MockMvc mockMvc;
    private ExpenseService expenseService;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        expenseService = mock(ExpenseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ExpenseController(expenseService))
                .setControllerAdvice(new GeneralExceptionAdvice())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("POST /api/expenses")
    class CreateExpense {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("올바른 요청 시 201과 등록된 지출 정보를 반환한다")
            void createExpense_success_returns201() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                ExpenseResponse response = new ExpenseResponse(
                        1L,
                        15000L,
                        "스타벅스 아메리카노",
                        LocalDateTime.of(2026, 8, 19, 12, 0),
                        2,
                        "친구랑 같이",
                        "SATISFIED",
                        false,
                        false,
                        LocalDateTime.of(2026, 8, 19, 12, 0));
                when(expenseService.createExpense(eq(1L), any())).thenReturn(response);

                mockMvc.perform(post("/api/expenses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.isSuccess").value(true))
                        .andExpect(jsonPath("$.result.id").value(1L))
                        .andExpect(jsonPath("$.result.amount").value(15000L))
                        .andExpect(jsonPath("$.result.title").value("스타벅스 아메리카노"))
                        .andExpect(jsonPath("$.result.categoryId").value(2))
                        .andExpect(jsonPath("$.result.emotion").value("SATISFIED"));

                verify(expenseService).createExpense(eq(1L), any());
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("필수 필드 누락 시 400을 반환한다")
            void createExpense_missingField_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                mockMvc.perform(post("/api/expenses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }

            @Test
            @DisplayName("금액이 0이면 400을 반환한다")
            void createExpense_zeroAmount_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                ExpenseRequest request =
                        new ExpenseRequest(0L, "테스트", LocalDateTime.of(2026, 8, 19, 12, 0), 1, null, null, null, null);

                mockMvc.perform(post("/api/expenses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }

            @Test
            @DisplayName("내역이 빈 문자열이면 400을 반환한다")
            void createExpense_blankTitle_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                ExpenseRequest request = new ExpenseRequest(
                        10000L, "  ", LocalDateTime.of(2026, 8, 19, 12, 0), 1, null, null, null, null);

                mockMvc.perform(post("/api/expenses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }

            @Test
            @DisplayName("유효하지 않은 카테고리이면 400을 반환한다")
            void createExpense_invalidCategory_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                when(expenseService.createExpense(eq(1L), any()))
                        .thenThrow(new GeneralException(GeneralErrorCode.INVALID_CATEGORY));

                ExpenseRequest request = new ExpenseRequest(
                        10000L, "테스트", LocalDateTime.of(2026, 8, 19, 12, 0), 99, null, null, null, null);

                mockMvc.perform(post("/api/expenses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("EXPENSE_400_001"));
            }

            @Test
            @DisplayName("유효하지 않은 감정 태그이면 400을 반환한다")
            void createExpense_invalidEmotion_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                when(expenseService.createExpense(eq(1L), any()))
                        .thenThrow(new GeneralException(GeneralErrorCode.INVALID_EMOTION));

                ExpenseRequest request = new ExpenseRequest(
                        15000L, "스타벅스 아메리카노", LocalDateTime.of(2026, 8, 19, 12, 0), 2, "친구랑 같이", "HAPPY", null, null);

                mockMvc.perform(post("/api/expenses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("EXPENSE_400_002"));
            }

            @Test
            @DisplayName("내역이 255자를 초과하면 400을 반환한다")
            void createExpense_titleTooLong_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                ExpenseRequest request = new ExpenseRequest(
                        10000L, "가".repeat(256), LocalDateTime.of(2026, 8, 19, 12, 0), 1, null, null, null, null);

                mockMvc.perform(post("/api/expenses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }
        }
    }

    @Nested
    @DisplayName("PUT /api/expenses/{expenseId}")
    class UpdateExpense {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("올바른 요청 시 200과 수정된 지출 정보를 반환한다")
            void updateExpense_success_returns200() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                ExpenseResponse response = new ExpenseResponse(
                        1L,
                        20000L,
                        "투썸 케이크",
                        LocalDateTime.of(2026, 8, 20, 14, 0),
                        3,
                        "생일 케이크",
                        "NECESSARY",
                        false,
                        false,
                        LocalDateTime.of(2026, 8, 19, 12, 0));
                when(expenseService.updateExpense(eq(1L), eq(1L), any())).thenReturn(response);

                ExpenseRequest request = new ExpenseRequest(
                        20000L, "투썸 케이크", LocalDateTime.of(2026, 8, 20, 14, 0), 3, "생일 케이크", "NECESSARY", null, null);

                mockMvc.perform(put("/api/expenses/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.isSuccess").value(true))
                        .andExpect(jsonPath("$.result.id").value(1L))
                        .andExpect(jsonPath("$.result.amount").value(20000L))
                        .andExpect(jsonPath("$.result.title").value("투썸 케이크"));

                verify(expenseService).updateExpense(eq(1L), eq(1L), any());
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("존재하지 않는 지출이면 404를 반환한다")
            void updateExpense_notFound_returns404() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                when(expenseService.updateExpense(eq(1L), eq(999L), any()))
                        .thenThrow(new GeneralException(GeneralErrorCode.EXPENSE_NOT_FOUND));

                mockMvc.perform(put("/api/expenses/999")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("EXPENSE_404_001"));
            }

            @Test
            @DisplayName("다른 사용자의 지출이면 403을 반환한다")
            void updateExpense_forbidden_returns403() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                when(expenseService.updateExpense(eq(1L), eq(1L), any()))
                        .thenThrow(new GeneralException(GeneralErrorCode.FORBIDDEN));

                mockMvc.perform(put("/api/expenses/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("AUTH_403_001"));
            }

            @Test
            @DisplayName("필수 필드 누락 시 400을 반환한다")
            void updateExpense_missingField_returns400() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                mockMvc.perform(put("/api/expenses/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("COMMON_400_002"));
            }
        }
    }

    @Nested
    @DisplayName("DELETE /api/expenses/{expenseId}")
    class DeleteExpense {

        @Nested
        @DisplayName("성공")
        class Success {

            @Test
            @DisplayName("올바른 요청 시 200을 반환한다")
            void deleteExpense_success_returns200() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                doNothing().when(expenseService).deleteExpense(1L, 1L);

                mockMvc.perform(delete("/api/expenses/1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.isSuccess").value(true));

                verify(expenseService).deleteExpense(1L, 1L);
            }
        }

        @Nested
        @DisplayName("실패")
        class Failure {

            @Test
            @DisplayName("존재하지 않는 지출이면 404를 반환한다")
            void deleteExpense_notFound_returns404() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                doThrow(new GeneralException(GeneralErrorCode.EXPENSE_NOT_FOUND))
                        .when(expenseService)
                        .deleteExpense(1L, 999L);

                mockMvc.perform(delete("/api/expenses/999"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("EXPENSE_404_001"));
            }

            @Test
            @DisplayName("다른 사용자의 지출이면 403을 반환한다")
            void deleteExpense_forbidden_returns403() throws Exception {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(1L, null));

                doThrow(new GeneralException(GeneralErrorCode.FORBIDDEN))
                        .when(expenseService)
                        .deleteExpense(1L, 1L);

                mockMvc.perform(delete("/api/expenses/1"))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.isSuccess").value(false))
                        .andExpect(jsonPath("$.code").value("AUTH_403_001"));
            }
        }
    }

    private ExpenseRequest validRequest() {
        return new ExpenseRequest(
                15000L, "스타벅스 아메리카노", LocalDateTime.of(2026, 8, 19, 12, 0), 2, "친구랑 같이", "SATISFIED", null, null);
    }
}
