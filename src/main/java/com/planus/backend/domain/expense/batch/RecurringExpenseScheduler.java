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
import org.springframework.transaction.annotation.Transactional;

/** 반복 지출 자동 등록 스케줄러. 매일 09:00에 해당 일자의 반복 지출을 자동 생성한다. */
@Component
public class RecurringExpenseScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringExpenseScheduler.class);

    private final ExpenseRepository expenseRepository;
    private final Clock clock;

    public RecurringExpenseScheduler(ExpenseRepository expenseRepository, Clock clock) {
        this.expenseRepository = expenseRepository;
        this.clock = clock;
    }

    /** 매일 09:00에 반복 지출을 자동 등록한다. ShedLock으로 중복 실행을 방지한다. */
    @Scheduled(cron = "${planus.expense.recurring-cron:0 0 9 * * *}")
    @SchedulerLock(name = "recurringExpense", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void registerRecurringExpenses() {
        executeRecurringExpenses();
    }

    /** 반복 지출 자동 등록 로직. 스케줄러 또는 수동 트리거에서 호출된다. */
    @Transactional
    public void executeRecurringExpenses() {
        LocalDate today = LocalDate.now(clock);
        int todayDay = today.getDayOfMonth();
        boolean isLastDayOfMonth = today.equals(YearMonth.from(today).atEndOfMonth());

        List<Expense> sources = expenseRepository.findByRecurringAndTypeAndSourceExpenseIdIsNull(true, "EXPENSE");

        log.info("반복 지출 자동 등록 시작 date={} 원본={}건", today, sources.size());

        int created = 0, skipped = 0;
        for (Expense source : sources) {
            int sourceDay = source.getExpenseDate().getDayOfMonth();

            // 오늘 일자와 원본 일자가 일치하는지 확인
            // 말일 처리: 원본이 29~31일이고 이번 달 마지막 날이 그보다 작으면, 마지막 날에 등록
            if (!shouldRegisterToday(sourceDay, todayDay, isLastDayOfMonth, today)) {
                continue;
            }

            // 이번 달 중복 확인
            LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
            LocalDateTime monthEnd = YearMonth.from(today).atEndOfMonth().atTime(23, 59, 59);

            if (expenseRepository.existsBySourceExpenseIdAndExpenseDateBetween(source.getId(), monthStart, monthEnd)) {
                skipped++;
                continue;
            }

            // 새 반복 지출 생성
            int actualDay = Math.min(sourceDay, YearMonth.from(today).lengthOfMonth());
            LocalDateTime newExpenseDate = today.withDayOfMonth(actualDay).atTime(9, 0);

            Expense newExpense = Expense.builder()
                    .userId(source.getUserId())
                    .type("EXPENSE")
                    .amount(source.getAmount())
                    .title(source.getTitle())
                    .expenseDate(newExpenseDate)
                    .categoryId(source.getCategoryId())
                    .recurring(true)
                    .planned(false)
                    .sourceExpenseId(source.getId())
                    .createdAt(LocalDateTime.now(clock))
                    .build();

            expenseRepository.save(newExpense);
            created++;
        }

        log.info("반복 지출 자동 등록 완료 date={} 생성={} 스킵={}", today, created, skipped);
    }

    /**
     * 오늘 해당 원본 지출을 등록해야 하는지 판단한다.
     *
     * @param sourceDay        원본 지출의 일(day)
     * @param todayDay         오늘의 일(day)
     * @param isLastDayOfMonth 오늘이 이번 달 마지막 날인지
     * @param today            오늘 날짜
     * @return 등록해야 하면 true
     */
    private boolean shouldRegisterToday(int sourceDay, int todayDay, boolean isLastDayOfMonth, LocalDate today) {
        if (sourceDay == todayDay) {
            return true;
        }
        // 원본이 31일인데 이번 달이 28~30일까지만 있는 경우 → 마지막 날에 등록
        if (isLastDayOfMonth && sourceDay > today.lengthOfMonth()) {
            return true;
        }
        return false;
    }
}
