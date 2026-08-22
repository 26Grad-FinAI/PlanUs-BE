package com.planus.backend.domain.home.service;

import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.home.dto.HomeSummaryResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 홈 화면 관련 비즈니스 로직. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeService {

    private final ExpenseRepository expenseRepository;
    private final BudgetRepository budgetRepository;
    private final UserAccountRepository userAccountRepository;

    /**
     * 월간 재정 요약을 조회한다.
     *
     * @param userId 사용자 ID
     * @param yearMonth 조회 월
     * @return 사용 금액, 예산, 소진율, 남은 예산
     */
    public HomeSummaryResponse getSummary(Long userId, YearMonth yearMonth) {
        LocalDateTime from = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime to = yearMonth.atEndOfMonth().atTime(LocalTime.MAX);

        long totalExpense = expenseRepository.sumExpensesByPeriod(userId, from, to);

        long budget = budgetRepository
                .findByUserIdAndYearMonth(userId, yearMonth.atDay(1))
                .map(Budget::getTotalBudget)
                .orElseGet(() -> findUserOrThrow(userId).availableBudget());

        long remainingBudget = budget - totalExpense;

        double burnRate = 0.0;
        if (budget > 0) {
            burnRate = BigDecimal.valueOf(totalExpense)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(budget), 1, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        return new HomeSummaryResponse(yearMonth.toString(), totalExpense, budget, burnRate, remainingBudget);
    }

    private UserAccount findUserOrThrow(Long userId) {
        return userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));
    }
}
