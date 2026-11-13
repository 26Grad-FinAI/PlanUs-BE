package com.planus.backend.domain.aibudget.event;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.planus.backend.domain.aibudget.service.BudgetPlanService;
import com.planus.backend.domain.user.event.ProfileSavedEvent;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileBudgetPlanTriggerTest {

    private BudgetPlanService budgetPlanService;
    private ProfileBudgetPlanTrigger trigger;

    @BeforeEach
    void setUp() {
        budgetPlanService = mock(BudgetPlanService.class);
        trigger = new ProfileBudgetPlanTrigger(budgetPlanService);
    }

    @Test
    @DisplayName("프로필 저장 이벤트를 받으면 해당 사용자의 예산 산출을 트리거한다")
    void onProfileSaved_triggersBudgetPlan() {
        trigger.onProfileSaved(new ProfileSavedEvent(1L));

        verify(budgetPlanService).triggerBudgetPlan(1L);
    }

    @Test
    @DisplayName("이미 진행 중인 산출이 있어도 예외를 밖으로 던지지 않는다")
    void onProfileSaved_inProgress_doesNotThrow() {
        doThrow(new GeneralException(GeneralErrorCode.BUDGET_PLAN_IN_PROGRESS))
                .when(budgetPlanService)
                .triggerBudgetPlan(1L);

        assertThatCode(() -> trigger.onProfileSaved(new ProfileSavedEvent(1L))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("예상하지 못한 예외가 나도 밖으로 던지지 않는다")
    void onProfileSaved_unexpectedError_doesNotThrow() {
        doThrow(new IllegalStateException("db down")).when(budgetPlanService).triggerBudgetPlan(1L);

        assertThatCode(() -> trigger.onProfileSaved(new ProfileSavedEvent(1L))).doesNotThrowAnyException();
    }
}
