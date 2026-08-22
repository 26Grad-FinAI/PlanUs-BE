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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 수입 등록 서비스. */
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
            throw new GeneralException(GeneralErrorCode.BUDGET_OVERFLOW);
        }

        return IncomeConverter.toIncomeResponse(saved);
    }

    private void validateCategoryId(Integer categoryId) {
        if (categoryId < MIN_CATEGORY_ID || categoryId > MAX_CATEGORY_ID) {
            throw new GeneralException(GeneralErrorCode.INVALID_CATEGORY);
        }
    }
}
