package com.planus.backend.domain.income.service;

import com.planus.backend.domain.aifeedback.entity.Budget;
import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.BudgetCategoryRepository;
import com.planus.backend.domain.aifeedback.repository.BudgetRepository;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.income.converter.IncomeConverter;
import com.planus.backend.domain.income.dto.IncomeRequest;
import com.planus.backend.domain.income.dto.IncomeResponse;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 수입 등록·수정·삭제 서비스. */
@Service
@RequiredArgsConstructor
public class IncomeService {

    private static final int MIN_CATEGORY_ID = 1;
    private static final int MAX_CATEGORY_ID = 11;

    private final ExpenseRepository expenseRepository;
    private final BudgetRepository budgetRepository;
    private final BudgetCategoryRepository budgetCategoryRepository;

    /**
     * 수입을 등록한다.
     *
     * @param userId 인증된 사용자 ID
     * @param request 수입 등록 요청
     * @return 등록된 수입 정보
     * @throws GeneralException categoryId가 1~11 범위 밖이거나 해당 월 예산이 없는 경우
     */
    @Transactional
    public IncomeResponse createIncome(Long userId, IncomeRequest request) {
        validateCategoryId(request.categoryId());

        Expense expense = IncomeConverter.toExpense(userId, request);
        Expense saved = expenseRepository.save(expense);

        LocalDate yearMonth = request.incomeDate().toLocalDate().withDayOfMonth(1);
        Budget budget = budgetRepository
                .findByUserIdAndYearMonth(userId, yearMonth)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.BUDGET_NOT_FOUND));

        BudgetCategory budgetCategory = budgetCategoryRepository
                .findByBudgetIdAndCategoryId(budget.getId(), request.categoryId())
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.BUDGET_CATEGORY_NOT_FOUND));

        try {
            budget.addTotalBudget(request.amount());
            budgetCategory.addAmount(request.amount());
        } catch (ArithmeticException e) {
            throw new GeneralException(GeneralErrorCode.BUDGET_OVERFLOW, e);
        }

        return IncomeConverter.toIncomeResponse(saved);
    }

    /**
     * 수입을 수정한다. 예산이 존재하면 기존 금액 차감 후 새 금액을 추가한다.
     *
     * <p>카테고리나 연월이 변경될 수 있으므로, 기존/신규 양쪽의 Budget·BudgetCategory를
     * 먼저 조회한 뒤 일괄 적용하여 비대칭 실행을 방지한다.
     *
     * @param userId   인증된 사용자 ID
     * @param incomeId 수정할 수입 ID
     * @param request  수입 수정 요청
     * @return 수정된 수입 정보
     */
    @Transactional
    public IncomeResponse updateIncome(Long userId, Long incomeId, IncomeRequest request) {
        Expense income = expenseRepository
                .findById(incomeId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.INCOME_NOT_FOUND));

        income.validateOwnership(userId);
        income.validateType("INCOME");
        validateCategoryId(request.categoryId());

        long oldAmount = income.getAmount();
        Integer oldCategoryId = income.getCategoryId();
        LocalDate oldYearMonth = income.getDate().withDayOfMonth(1);
        LocalDate newYearMonth = request.incomeDate().toLocalDate().withDayOfMonth(1);

        // 기존·신규 Budget/BudgetCategory를 미리 조회
        Optional<Budget> oldBudgetOpt = budgetRepository.findByUserIdAndYearMonth(userId, oldYearMonth);
        Optional<BudgetCategory> oldBcOpt = oldBudgetOpt.flatMap(
                b -> budgetCategoryRepository.findByBudgetIdAndCategoryId(b.getId(), oldCategoryId));

        Optional<Budget> newBudgetOpt;
        Optional<BudgetCategory> newBcOpt;
        if (oldYearMonth.equals(newYearMonth)) {
            newBudgetOpt = oldBudgetOpt;
        } else {
            newBudgetOpt = budgetRepository.findByUserIdAndYearMonth(userId, newYearMonth);
        }
        newBcOpt = newBudgetOpt.flatMap(
                b -> budgetCategoryRepository.findByBudgetIdAndCategoryId(b.getId(), request.categoryId()));

        income.updateIncome(
                request.amount(), request.title(), request.incomeDate(), request.categoryId(), request.memo());

        // 예산 조정: 조회 결과가 있는 쪽만 일괄 적용
        try {
            if (oldBudgetOpt.isPresent() && oldBcOpt.isPresent()) {
                oldBudgetOpt.get().addTotalBudget(-oldAmount);
                oldBcOpt.get().addAmount(-oldAmount);
            }
            if (newBudgetOpt.isPresent() && newBcOpt.isPresent()) {
                newBudgetOpt.get().addTotalBudget(request.amount());
                newBcOpt.get().addAmount(request.amount());
            }
        } catch (ArithmeticException e) {
            throw new GeneralException(GeneralErrorCode.BUDGET_OVERFLOW, e);
        }

        return IncomeConverter.toIncomeResponse(income);
    }

    /**
     * 수입을 삭제한다. 예산이 존재하면 금액을 차감한다.
     *
     * @param userId   인증된 사용자 ID
     * @param incomeId 삭제할 수입 ID
     */
    @Transactional
    public void deleteIncome(Long userId, Long incomeId) {
        Expense income = expenseRepository
                .findById(incomeId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.INCOME_NOT_FOUND));

        income.validateOwnership(userId);
        income.validateType("INCOME");

        // 예산에서 금액 차감: budget이 없으면 스킵
        LocalDate yearMonth = income.getDate().withDayOfMonth(1);
        adjustBudget(userId, yearMonth, income.getCategoryId(), -income.getAmount());

        expenseRepository.delete(income);
    }

    /**
     * 예산 금액을 조정한다. budget이나 budgetCategory가 없으면 스킵한다.
     */
    private void adjustBudget(Long userId, LocalDate yearMonth, Integer categoryId, long amount) {
        Optional<Budget> budgetOpt = budgetRepository.findByUserIdAndYearMonth(userId, yearMonth);
        if (budgetOpt.isEmpty()) {
            return;
        }
        Budget budget = budgetOpt.get();

        Optional<BudgetCategory> budgetCategoryOpt =
                budgetCategoryRepository.findByBudgetIdAndCategoryId(budget.getId(), categoryId);
        if (budgetCategoryOpt.isEmpty()) {
            return;
        }

        try {
            budget.addTotalBudget(amount);
            budgetCategoryOpt.get().addAmount(amount);
        } catch (ArithmeticException e) {
            throw new GeneralException(GeneralErrorCode.BUDGET_OVERFLOW, e);
        }
    }

    private void validateCategoryId(Integer categoryId) {
        if (categoryId < MIN_CATEGORY_ID || categoryId > MAX_CATEGORY_ID) {
            throw new GeneralException(GeneralErrorCode.INVALID_CATEGORY);
        }
    }
}
