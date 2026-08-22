package com.planus.backend.domain.home.service;

import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.home.dto.HomeCalendarResponse;
import com.planus.backend.domain.home.dto.HomeCalendarResponse.DailySummary;
import com.planus.backend.domain.home.dto.HomeDailyResponse;
import com.planus.backend.domain.home.dto.HomeDailyResponse.TransactionDetail;
import com.planus.backend.domain.home.dto.HomeSummaryResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    /**
     * 캘린더 뷰용 날짜별 지출·수입 합계를 조회한다.
     *
     * @param userId 사용자 ID
     * @param yearMonth 조회 월
     * @return 날짜별 지출·수입 합계 목록
     */
    public HomeCalendarResponse getCalendar(Long userId, YearMonth yearMonth) {
        LocalDateTime from = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime to = yearMonth.atEndOfMonth().atTime(LocalTime.MAX);

        List<Expense> expenses = expenseRepository.findByUserIdAndExpenseDateBetween(userId, from, to);

        Map<LocalDate, List<Expense>> grouped = expenses.stream().collect(Collectors.groupingBy(Expense::getDate));

        List<DailySummary> dailySummaries = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    long totalExpense = entry.getValue().stream()
                            .filter(Expense::isExpense)
                            .mapToLong(Expense::getAmount)
                            .sum();
                    long totalIncome = entry.getValue().stream()
                            .filter(e -> !e.isExpense())
                            .mapToLong(Expense::getAmount)
                            .sum();
                    return new DailySummary(entry.getKey(), totalExpense, totalIncome);
                })
                .toList();

        return new HomeCalendarResponse(yearMonth.toString(), dailySummaries);
    }

    /**
     * 특정 날짜의 상세 거래 내역을 조회한다.
     *
     * @param userId 사용자 ID
     * @param date 조회 날짜
     * @return 해당 날짜의 지출·수입 합계 및 거래 상세 목록
     */
    public HomeDailyResponse getDailyDetail(Long userId, LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.atTime(LocalTime.MAX);

        List<Expense> expenses = expenseRepository.findByUserIdAndExpenseDateBetween(userId, from, to);

        long totalExpense = expenses.stream()
                .filter(Expense::isExpense)
                .mapToLong(Expense::getAmount)
                .sum();
        long totalIncome = expenses.stream()
                .filter(e -> !e.isExpense())
                .mapToLong(Expense::getAmount)
                .sum();

        List<TransactionDetail> transactions = expenses.stream()
                .map(e -> new TransactionDetail(
                        e.getId(),
                        e.getType(),
                        e.getAmount(),
                        e.getTitle(),
                        e.getCategoryId(),
                        e.getMemo(),
                        e.getEmotion(),
                        e.isRecurring(),
                        e.isPlanned(),
                        e.getExpenseDate().toLocalTime()))
                .toList();

        return new HomeDailyResponse(date, totalExpense, totalIncome, transactions);
    }

    private UserAccount findUserOrThrow(Long userId) {
        return userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));
    }
}
