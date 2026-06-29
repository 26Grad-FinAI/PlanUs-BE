package com.planus.backend.domain.aifeedback;

import com.planus.backend.domain.aifeedback.repo.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** AI-01 주간 배치. 기본 매주 월 09:00. 대상 주 = 직전 주(월~일). */
@Component
public class WeeklyFeedbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyFeedbackScheduler.class);

    private final UserAccountRepository userRepo;
    private final AiFeedbackService service;
    private final Clock clock;

    public WeeklyFeedbackScheduler(UserAccountRepository userRepo, AiFeedbackService service, Clock clock) {
        this.userRepo = userRepo; this.service = service; this.clock = clock;
    }

    @Scheduled(cron = "${planus.ai.weekly-cron:0 0 9 * * MON}")
    public void runWeekly() {
        LocalDate weekEnd = LocalDate.now(clock).with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        List<Long> userIds = userRepo.findAllIds();
        log.info("AI-01 주간 시작 weekEnd={} 대상={}명", weekEnd, userIds.size());

        int ok = 0, fail = 0;
        for (Long userId : userIds) {
            try { service.generateWeekly(userId, weekEnd); ok++; }
            catch (Exception e) { fail++; log.warn("AI-01 실패 user={} : {}", userId, e.toString()); }
        }
        log.info("AI-01 주간 완료 weekEnd={} 성공={} 실패={}", weekEnd, ok, fail);
    }
}
