package com.planus.backend.domain.aifeedback.batch;

import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.domain.aifeedback.service.AiFeedbackService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AI 월말 결산 배치. 기본 매월 1일 03:00. 대상 월 = 전월. */
@Component
public class MonthEndFeedbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthEndFeedbackScheduler.class);

    private final UserAccountRepository userRepo;
    private final AiFeedbackService service;
    private final Clock clock;

    public MonthEndFeedbackScheduler(UserAccountRepository userRepo, AiFeedbackService service, Clock clock) {
        this.userRepo = userRepo;
        this.service = service;
        this.clock = clock;
    }

    /** 매월 1일 03:00에 전체 사용자 대상 월말 결산 피드백을 생성한다. 실패한 사용자는 로그 경고 후 건너뛴다. */
    @Scheduled(cron = "${planus.ai.month-end-cron:0 0 3 1 * *}")
    @SchedulerLock(name = "monthEndFeedback", lockAtMostFor = "2h", lockAtLeastFor = "1h")
    public void runMonthEnd() {
        YearMonth prevMonth = YearMonth.from(LocalDate.now(clock)).minusMonths(1);
        List<Long> userIds = userRepo.findAllIds();
        log.info("AI 월말결산 시작 month={} 대상={}명", prevMonth, userIds.size());

        int ok = 0, skipped = 0, fail = 0;
        for (Long userId : userIds) {
            try {
                if (service.generateMonthEnd(userId, prevMonth).isPresent()) {
                    ok++;
                } else {
                    skipped++;
                    log.info("AI 월말결산 스킵 user={} month={}", userId, prevMonth);
                }
            } catch (Exception e) {
                fail++;
                log.warn("AI 월말결산 실패 user={}", userId, e);
            }
        }
        log.info("AI 월말결산 완료 month={} 성공={} 스킵={} 실패={}", prevMonth, ok, skipped, fail);
    }
}
