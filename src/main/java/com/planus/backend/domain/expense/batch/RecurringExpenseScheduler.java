package com.planus.backend.domain.expense.batch;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 반복 지출 자동 등록 스케줄러. 매일 03:00에 해당 일자의 반복 지출을 자동 생성한다. */
@Component
public class RecurringExpenseScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringExpenseScheduler.class);

    private final ExpenseRepository expenseRepository;
    private final RecurringExpenseItemProcessor itemProcessor;
    private final Clock clock;

    public RecurringExpenseScheduler(
            ExpenseRepository expenseRepository, RecurringExpenseItemProcessor itemProcessor, Clock clock) {
        this.expenseRepository = expenseRepository;
        this.itemProcessor = itemProcessor;
        this.clock = clock;
    }

    /** 매일 03:00에 반복 지출을 자동 등록한다. ShedLock으로 중복 실행을 방지한다. */
    @Scheduled(cron = "${planus.expense.recurring-cron:0 0 3 * * *}")
    @SchedulerLock(name = "recurringExpense", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void registerRecurringExpenses() {
        executeRecurringExpenses();
    }

    /** 반복 지출 자동 등록 로직. 스케줄러 또는 수동 트리거에서 호출된다. */
    public void executeRecurringExpenses() {
        // 예정일이 경과한 계획 지출의 planned 플래그를 갱신한다.
        int cleared = itemProcessor.clearExpiredPlanned(LocalDateTime.now(clock));
        if (cleared > 0) {
            log.info("예정 지출 planned 갱신 {}건", cleared);
        }

        LocalDate today = LocalDate.now(clock);
        int todayDay = today.getDayOfMonth();
        boolean isLastDayOfMonth = today.equals(YearMonth.from(today).atEndOfMonth());

        List<Expense> sources = expenseRepository.findByRecurringAndTypeAndSourceExpenseIdIsNull(true, "EXPENSE");

        log.info("반복 지출 자동 등록 시작 date={} 원본={}건", today, sources.size());

        int created = 0, skipped = 0, fail = 0;
        for (Expense source : sources) {
            // 원본 지출일 당월 이전에만 생성 (당일·미래 원본은 스킵)
            if (!source.getExpenseDate().toLocalDate().isBefore(today)) {
                continue;
            }

            // 최신 복사본이 존재하고 recurring=false이면 스킵
            Expense latestCopy = expenseRepository.findLatestCopyBySourceExpenseId(source.getId());
            if (latestCopy != null && !latestCopy.isRecurring()) {
                continue;
            }

            int sourceDay = source.getExpenseDate().getDayOfMonth();
            if (!shouldRegisterToday(sourceDay, todayDay, isLastDayOfMonth, today)) {
                continue;
            }

            try {
                if (itemProcessor.process(source, today)) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                fail++;
                log.warn("반복 지출 자동 등록 실패 sourceId={} user={}", source.getId(), source.getUserId(), e);
            }
        }

        log.info("반복 지출 자동 등록 완료 date={} 생성={} 스킵={} 실패={}", today, created, skipped, fail);
    }

    private boolean shouldRegisterToday(int sourceDay, int todayDay, boolean isLastDayOfMonth, LocalDate today) {
        if (sourceDay == todayDay) {
            return true;
        }
        if (isLastDayOfMonth && sourceDay > today.lengthOfMonth()) {
            return true;
        }
        return false;
    }
}
