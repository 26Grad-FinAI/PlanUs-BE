package com.planus.backend.domain.expense.batch;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 반복 지출 항목 단위 처리기. 항목별 독립 트랜잭션으로 실패를 격리한다. */
@Component
public class RecurringExpenseItemProcessor {

    private final ExpenseRepository expenseRepository;
    private final Clock clock;

    public RecurringExpenseItemProcessor(ExpenseRepository expenseRepository, Clock clock) {
        this.expenseRepository = expenseRepository;
        this.clock = clock;
    }

    /**
     * 단일 원본 지출에 대해 이번 달 반복 지출을 생성한다.
     *
     * @param source 원본 반복 지출
     * @param today  오늘 날짜
     * @return 생성되었으면 true, 중복으로 스킵되었으면 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean process(Expense source, LocalDate today) {
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = YearMonth.from(today).atEndOfMonth().atTime(23, 59, 59);

        if (expenseRepository.existsBySourceExpenseIdAndExpenseDateBetween(source.getId(), monthStart, monthEnd)) {
            return false;
        }

        int sourceDay = source.getExpenseDate().getDayOfMonth();
        int actualDay = Math.min(sourceDay, YearMonth.from(today).lengthOfMonth());
        LocalDateTime newExpenseDate = today.withDayOfMonth(actualDay).atTime(3, 0);

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
        return true;
    }

    /**
     * 예정일이 경과한 계획 지출의 planned 플래그를 false로 갱신한다.
     *
     * @param now 현재 일시
     * @return 갱신된 행 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int clearExpiredPlanned(LocalDateTime now) {
        return expenseRepository.clearExpiredPlanned(now);
    }
}
