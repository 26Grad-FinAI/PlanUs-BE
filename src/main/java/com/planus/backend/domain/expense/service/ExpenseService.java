package com.planus.backend.domain.expense.service;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.expense.converter.ExpenseConverter;
import com.planus.backend.domain.expense.dto.ExpenseRequest;
import com.planus.backend.domain.expense.dto.ExpenseResponse;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 지출 등록·수정·삭제 서비스. */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private static final Set<String> VALID_EMOTIONS =
            Set.of("NECESSARY", "SATISFIED", "IMPULSE", "STRESS_RELIEF", "REGRET");

    private static final int MIN_CATEGORY_ID = 1;
    private static final int MAX_CATEGORY_ID = 11;

    private final ExpenseRepository expenseRepository;

    /**
     * 지출을 등록한다.
     *
     * @param userId  인증된 사용자 ID
     * @param request 지출 등록 요청 (금액·내역·날짜·카테고리·메모·감정태그·고정지출여부·계획지출여부)
     * @return 등록된 지출 정보
     * @throws GeneralException categoryId가 1~11 범위 밖이거나 emotion이 유효하지 않은 경우
     */
    @Transactional
    public ExpenseResponse createExpense(Long userId, ExpenseRequest request) {
        validateCategoryId(request.categoryId());
        validateEmotion(request.emotion());

        Expense expense = ExpenseConverter.toExpense(userId, request);
        Expense saved = expenseRepository.save(expense);
        return ExpenseConverter.toExpenseResponse(saved);
    }

    /**
     * 지출을 수정한다.
     *
     * @param userId    인증된 사용자 ID
     * @param expenseId 수정할 지출 ID
     * @param request   지출 수정 요청
     * @return 수정된 지출 정보
     */
    @Transactional
    public ExpenseResponse updateExpense(Long userId, Long expenseId, ExpenseRequest request) {
        Expense expense = expenseRepository
                .findById(expenseId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.EXPENSE_NOT_FOUND));

        expense.validateOwnership(userId);
        expense.validateType("EXPENSE");
        validateCategoryId(request.categoryId());
        validateEmotion(request.emotion());

        expense.updateExpense(
                request.amount(),
                request.title(),
                request.expenseDate(),
                request.categoryId(),
                request.memo(),
                request.emotion(),
                Boolean.TRUE.equals(request.isRecurring()),
                Boolean.TRUE.equals(request.isPlanned()));

        return ExpenseConverter.toExpenseResponse(expense);
    }

    /**
     * 지출을 삭제한다.
     *
     * @param userId    인증된 사용자 ID
     * @param expenseId 삭제할 지출 ID
     */
    @Transactional
    public void deleteExpense(Long userId, Long expenseId) {
        Expense expense = expenseRepository
                .findById(expenseId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.EXPENSE_NOT_FOUND));

        expense.validateOwnership(userId);
        expense.validateType("EXPENSE");

        expenseRepository.delete(expense);
    }

    private void validateCategoryId(Integer categoryId) {
        if (categoryId < MIN_CATEGORY_ID || categoryId > MAX_CATEGORY_ID) {
            throw new GeneralException(GeneralErrorCode.INVALID_CATEGORY);
        }
    }

    private void validateEmotion(String emotion) {
        if (emotion != null && !VALID_EMOTIONS.contains(emotion)) {
            throw new GeneralException(GeneralErrorCode.INVALID_EMOTION);
        }
    }
}
