package com.planus.backend.domain.savingssimulation.event;

import com.planus.backend.domain.aifeedback.event.FeedbackCreatedEvent;
import com.planus.backend.domain.savingssimulation.service.SavingsSimulationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 피드백 생성 후 절약 시뮬레이션 suggest를 자동 실행한다.
 *
 * <p>{@code AFTER_COMMIT} 단계에서 실행되므로 피드백 트랜잭션에 영향을 주지 않는다.
 * 현재 단계에서는 로깅만 수행하며, 향후 FCM 푸시·캐싱 연동의 토대가 된다.
 */
@Component
public class SavingsSimulationAutoRunner {

    private static final Logger log = LoggerFactory.getLogger(SavingsSimulationAutoRunner.class);

    private final SavingsSimulationService simulationService;

    public SavingsSimulationAutoRunner(SavingsSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFeedbackCreated(FeedbackCreatedEvent event) {
        try {
            log.info("SavingsSimulationAutoRunner 시작 — userId={}, yearMonth={}", event.userId(), event.yearMonth());
            var result = simulationService.suggest(event.userId(), event.yearMonth());
            log.info(
                    "SavingsSimulationAutoRunner 완료 — userId={}, yearMonth={}, actions={}, totalPossibleReduction={}",
                    event.userId(),
                    event.yearMonth(),
                    result.actions().size(),
                    result.totalPossibleReduction());
        } catch (Exception e) {
            log.warn(
                    "SavingsSimulationAutoRunner 실패 — userId={}, yearMonth={}: {}",
                    event.userId(),
                    event.yearMonth(),
                    e.getMessage());
        }
    }
}
