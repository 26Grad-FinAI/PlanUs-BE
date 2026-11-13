package com.planus.backend.domain.aibudget.event;

import com.planus.backend.domain.aibudget.service.BudgetPlanService;
import com.planus.backend.domain.user.event.ProfileSavedEvent;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 프로필 저장·수정 후 AI 예산 산출을 자동으로 시작한다.
 *
 * <p>{@code AFTER_COMMIT} 단계에서 실행한다. {@code triggerBudgetPlan}은 별도 트랜잭션이라, 프로필 트랜잭션이 커밋되기 전에
 * 부르면 바뀐 프로필을 읽지 못한다. 트리거가 실패해도 프로필 저장 결과에는 영향을 주지 않는다.
 */
@Component
public class ProfileBudgetPlanTrigger {

    private static final Logger log = LoggerFactory.getLogger(ProfileBudgetPlanTrigger.class);

    private final BudgetPlanService budgetPlanService;

    public ProfileBudgetPlanTrigger(BudgetPlanService budgetPlanService) {
        this.budgetPlanService = budgetPlanService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProfileSaved(ProfileSavedEvent event) {
        try {
            budgetPlanService.triggerBudgetPlan(event.userId());
            log.info("프로필 저장 후 예산 산출 트리거 userId={}", event.userId());
        } catch (GeneralException e) {
            // 이미 진행 중(BUDGET_PLAN_IN_PROGRESS) 등 — 프로필 저장은 이미 끝났으므로 로그만 남긴다
            log.warn("프로필 저장 후 예산 산출 트리거 생략 userId={}: {}", event.userId(), e.getMessage());
        } catch (Exception e) {
            log.error("프로필 저장 후 예산 산출 트리거 실패 userId={}", event.userId(), e);
        }
    }
}
