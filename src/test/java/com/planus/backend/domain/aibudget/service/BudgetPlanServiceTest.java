package com.planus.backend.domain.aibudget.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aibudget.client.AiBudgetClient;
import com.planus.backend.domain.aibudget.entity.BudgetPlanRequest;
import com.planus.backend.domain.aibudget.entity.BudgetPlanStatus;
import com.planus.backend.domain.aibudget.repository.BudgetPlanRequestRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class BudgetPlanServiceTest {

    private UserAccountRepository userAccountRepository;
    private BudgetPlanRequestRepository budgetPlanRequestRepository;
    private ExpenseRepository expenseRepository;
    private BudgetRepository budgetRepository;
    private BudgetCategoryRepository budgetCategoryRepository;
    private AiBudgetClient aiBudgetClient;
    private FcmService fcmService;
    private ObjectMapper objectMapper;
    private ApplicationContext applicationContext;

    private BudgetPlanService service;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        budgetPlanRequestRepository = mock(BudgetPlanRequestRepository.class);
        expenseRepository = mock(ExpenseRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        budgetCategoryRepository = mock(BudgetCategoryRepository.class);
        aiBudgetClient = mock(AiBudgetClient.class);
        fcmService = mock(FcmService.class);
        objectMapper = new ObjectMapper();
        applicationContext = mock(ApplicationContext.class);

        service = new BudgetPlanService(
                userAccountRepository,
                budgetPlanRequestRepository,
                expenseRepository,
                budgetRepository,
                budgetCategoryRepository,
                aiBudgetClient,
                fcmService,
                objectMapper,
                applicationContext);
    }

    @Nested
    @DisplayName("triggerBudgetPlan()")
    class TriggerBudgetPlan {

        @Test
        @DisplayName("PENDING 상태의 기존 요청이 있으면 예외 발생")
        void existingPending_throws() {
            Long userId = 1L;
            LocalDate yearMonth = LocalDate.now().withDayOfMonth(1);

            BudgetPlanRequest existing = BudgetPlanRequest.builder()
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .build();
            // 기본 상태가 PENDING

            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.triggerBudgetPlan(userId)).isInstanceOf(GeneralException.class);
        }

        @Test
        @DisplayName("IN_PROGRESS 상태의 기존 요청이 있으면 예외 발생")
        void existingInProgress_throws() {
            Long userId = 1L;
            LocalDate yearMonth = LocalDate.now().withDayOfMonth(1);

            BudgetPlanRequest existing = BudgetPlanRequest.builder()
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .build();
            existing.markInProgress();

            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.triggerBudgetPlan(userId)).isInstanceOf(GeneralException.class);
        }
    }

    @Nested
    @DisplayName("markInProgress()")
    class MarkInProgress {

        @Test
        @DisplayName("정상적으로 상태를 IN_PROGRESS로 변경")
        void success() {
            Long userId = 1L;
            LocalDate yearMonth = LocalDate.of(2026, 10, 1);

            BudgetPlanRequest request = BudgetPlanRequest.builder()
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .build();

            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.of(request));

            service.markInProgress(userId, yearMonth);

            assertThat(request.getStatus()).isEqualTo(BudgetPlanStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("요청이 없으면 예외 발생")
        void notFound_throws() {
            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(anyLong(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.markInProgress(1L, LocalDate.of(2026, 10, 1)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("markFailed()")
    class MarkFailed {

        @Test
        @DisplayName("정상적으로 상태를 FAILED로 변경하고 에러 메시지 저장")
        void success() {
            Long userId = 1L;
            LocalDate yearMonth = LocalDate.of(2026, 10, 1);

            BudgetPlanRequest request = BudgetPlanRequest.builder()
                    .userId(userId)
                    .yearMonth(yearMonth)
                    .build();

            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(userId, yearMonth))
                    .thenReturn(Optional.of(request));

            service.markFailed(userId, yearMonth, "timeout");

            assertThat(request.getStatus()).isEqualTo(BudgetPlanStatus.FAILED);
            assertThat(request.getErrorMessage()).isEqualTo("timeout");
        }

        @Test
        @DisplayName("요청이 없어도 예외 발생하지 않음")
        void notFound_noException() {
            when(budgetPlanRequestRepository.findByUserIdAndYearMonth(anyLong(), any()))
                    .thenReturn(Optional.empty());

            assertThatCode(() -> service.markFailed(1L, LocalDate.of(2026, 10, 1), "error"))
                    .doesNotThrowAnyException();
        }
    }
}
