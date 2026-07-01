package com.planus.backend.domain.aifeedback;

import com.planus.backend.domain.aifeedback.repository.UserAccountRepository;
import com.planus.backend.domain.aifeedback.service.AiFeedbackService;
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

    /**
     * @param userRepo 사용자 ID 조회 리포지토리
     * @param service  AI 피드백 생성 서비스
     * @param clock    시각 제공 (테스트 시 고정 시계 주입 가능)
     */
    public WeeklyFeedbackScheduler(UserAccountRepository userRepo, AiFeedbackService service, Clock clock) {
        this.userRepo = userRepo; this.service = service; this.clock = clock;
    }

    /** 매주 월요일 09:00에 전체 사용자 대상 주간 피드백을 생성한다. 실패한 사용자는 로그 경고 후 건너뛴다. */
    @Scheduled(cron = "${planus.ai.weekly-cron:0 0 9 * * MON}")
    public void runWeekly() {
        LocalDate weekEnd = LocalDate.now(clock).with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        List<Long> userIds = userRepo.findAllIds();
        log.info("AI-01 주간 시작 weekEnd={} 대상={}명", weekEnd, userIds.size());

        int ok = 0, skipped = 0, fail = 0;
        for (Long userId : userIds) {
            try {
                if (service.generateWeekly(userId, weekEnd).isPresent()) {
                    ok++;
                } else {
                    skipped++;
                    log.info("AI-01 스킵 user={} weekEnd={}", userId, weekEnd);
                }
            } catch (Exception e) { fail++; log.warn("AI-01 실패 user={}", userId, e); }
        }
        log.info("AI-01 주간 완료 weekEnd={} 성공={} 스킵={} 실패={}", weekEnd, ok, skipped, fail);
    }
}
