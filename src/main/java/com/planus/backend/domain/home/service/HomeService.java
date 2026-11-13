package com.planus.backend.domain.home.service;

import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.home.dto.HomeCalendarResponse;
import com.planus.backend.domain.home.dto.HomeCalendarResponse.DailySummary;
import com.planus.backend.domain.home.dto.HomeDailyResponse;
import com.planus.backend.domain.home.dto.HomeDailyResponse.TransactionDetail;
import com.planus.backend.domain.home.dto.HomeRecentResponse;
import com.planus.backend.domain.home.dto.HomeRecentResponse.RecentTransaction;
import com.planus.backend.domain.home.dto.HomeSummaryResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 홈 화면 관련 비즈니스 로직. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeService {

    private static final int MAX_RECENT_PAGE_SIZE = 100;

    private final ExpenseRepository expenseRepository;
    private final BudgetRepository budgetRepository;
    private final UserAccountRepository userAccountRepository;

    /**
     * 월간 재정 요약을 조회한다.
     *
     * @param userId 사용자 ID
     * @param yearMonth 조회 월
     * @return 사용 금액, 예산, 소진율, 남은 예산, 수입·지출 상세
     */
    public HomeSummaryResponse getSummary(Long userId, YearMonth yearMonth) {
        LocalDateTime from = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime to = yearMonth.atEndOfMonth().atTime(LocalTime.MAX);

        UserAccount user = findUserOrThrow(userId);

        long totalExpense = expenseRepository.sumExpensesByPeriod(userId, from, to);

        long budget = budgetRepository
                .findByUserIdAndYearMonth(userId, yearMonth.atDay(1))
                .map(Budget::getTotalBudget)
                .orElseGet(user::availableBudget);

        long remainingBudget = budget - totalExpense;

        double burnRate = 0.0;
        if (budget > 0) {
            burnRate = BigDecimal.valueOf(totalExpense)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(budget), 1, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        // 수입 상세: 월 소득(프로필) + 추가 소득(거래 내역)
        long monthlyIncome = user.getMonthlyIncome();
        long additionalIncome = expenseRepository.sumIncomeByPeriod(userId, from, to);
        long totalIncome = monthlyIncome + additionalIncome;
        var incomeSummary = new HomeSummaryResponse.IncomeSummary(totalIncome, monthlyIncome, additionalIncome);

        // 지출 상세: 고정 지출(프로필) + 나머지(거래 내역)
        long fixedExpense = user.totalFixedExpenses();
        long variableExpense = totalExpense;
        long totalExpenseAll = fixedExpense + variableExpense;
        var expenseSummary = new HomeSummaryResponse.ExpenseSummary(totalExpenseAll, fixedExpense, variableExpense);

        return new HomeSummaryResponse(
                yearMonth.toString(), totalExpense, budget, burnRate, remainingBudget, incomeSummary, expenseSummary);
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

    /**
     * 선택 월의 거래를 거래 일시와 ID 내림차순으로 조회한다.
     *
     * @param userId 사용자 ID
     * @param yearMonth 조회 월
     * @param size 페이지 크기
     * @param cursor 직전 페이지의 마지막 거래를 가리키는 커서
     * @return 최근 거래 목록과 다음 페이지 커서
     */
    public HomeRecentResponse getRecentTransactions(Long userId, YearMonth yearMonth, int size, String cursor) {
        if (yearMonth.getYear() < 1 || yearMonth.getYear() > 9999) {
            throw new GeneralException(GeneralErrorCode.VALIDATION_ERROR, "조회 월은 yyyy-MM 형식이어야 합니다.");
        }
        if (size < 1 || size > MAX_RECENT_PAGE_SIZE) {
            throw new GeneralException(GeneralErrorCode.VALIDATION_ERROR, "페이지 크기는 1~100이어야 합니다.");
        }

        LocalDateTime from = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime to = from.plusMonths(1);
        PageRequest page = PageRequest.of(0, size + 1);
        List<Expense> recent;
        if (cursor == null) {
            recent = expenseRepository.findRecentByPeriod(userId, from, to, page);
        } else {
            RecentCursor position = decodeRecentCursor(cursor, yearMonth);
            recent = expenseRepository.findRecentByPeriodAfterCursor(
                    userId, from, to, position.date(), position.id(), page);
        }

        List<RecentTransaction> transactions = recent.stream()
                .limit(size)
                .map(e -> new RecentTransaction(
                        e.getId(),
                        e.getType(),
                        e.isExpense() ? -e.getAmount() : e.getAmount(),
                        e.getTitle(),
                        e.getCategoryId(),
                        e.getDate(),
                        e.getExpenseDate().toLocalTime()))
                .toList();
        String nextCursor = recent.size() > size ? encodeRecentCursor(recent.get(size - 1)) : null;
        return new HomeRecentResponse(transactions, nextCursor);
    }

    private String encodeRecentCursor(Expense expense) {
        String position = expense.getExpenseDate() + "|" + expense.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
    }

    private RecentCursor decodeRecentCursor(String cursor, YearMonth yearMonth) {
        try {
            if (cursor.length() > 80) {
                throw new IllegalArgumentException();
            }
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] position = decoded.split("\\|", -1);
            if (position.length != 2) {
                throw new IllegalArgumentException();
            }
            LocalDateTime date = LocalDateTime.parse(position[0]);
            long id = Long.parseLong(position[1]);
            if (id <= 0 || !YearMonth.from(date).equals(yearMonth)) {
                throw new IllegalArgumentException();
            }
            return new RecentCursor(date, id);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new GeneralException(GeneralErrorCode.VALIDATION_ERROR, "조회 월에 유효한 거래 커서가 아닙니다.");
        }
    }

    private record RecentCursor(LocalDateTime date, long id) {}

    private UserAccount findUserOrThrow(Long userId) {
        return userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));
    }
}
