package com.planus.backend.domain.aibudget.batch;

import com.planus.backend.domain.aibudget.service.BudgetPlanService;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 월초 AI 예산 산출 배치. 매월 1일 02:00에 프로필 완료 유저에 대해 예산 산출을 트리거한다. */
@Component
public class MonthlyBudgetPlanScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyBudgetPlanScheduler.class);

    private final UserAccountRepository userRepo;
    private final BudgetPlanService budgetPlanService;

    public MonthlyBudgetPlanScheduler(UserAccountRepository userRepo, BudgetPlanService budgetPlanService) {
        this.userRepo = userRepo;
        this.budgetPlanService = budgetPlanService;
    }

    @Scheduled(cron = "0 0 2 1 * *")
    @SchedulerLock(name = "monthlyBudgetPlan", lockAtMostFor = "4h", lockAtLeastFor = "2h")
    public void runMonthlyBudgetPlan() {
        List<Long> userIds = userRepo.findAllIds();
        log.info("월간 AI 예산 산출 시작 대상={}명", userIds.size());

        int ok = 0, skipped = 0, fail = 0;
        for (Long userId : userIds) {
            try {
                UserAccount user = userRepo.findById(userId).orElse(null);
                if (user == null || !user.isProfileCompleted()) {
                    skipped++;
                    continue;
                }
                budgetPlanService.triggerBudgetPlan(userId);
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("월간 AI 예산 산출 실패 user={}: {}", userId, e.getMessage());
            }
        }
        log.info("월간 AI 예산 산출 완료 성공={} 스킵={} 실패={}", ok, skipped, fail);
    }
}
