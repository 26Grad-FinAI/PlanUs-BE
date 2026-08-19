package com.planus.backend.domain.income.converter;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.income.dto.IncomeRequest;
import com.planus.backend.domain.income.dto.IncomeResponse;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Income DTO ↔ Expense 엔티티 변환 유틸리티. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class IncomeConverter {

    /**
     * 수입 등록 요청을 Expense 엔티티로 변환한다.
     *
     * @param userId 인증된 사용자 ID
     * @param request 수입 등록 요청
     * @return Expense 엔티티 (type = "INCOME")
     */
    public static Expense toExpense(Long userId, IncomeRequest request) {
        return Expense.builder()
                .userId(userId)
                .type("INCOME")
                .amount(request.amount())
                .title(request.title())
                .expenseDate(request.incomeDate())
                .categoryId(request.categoryId())
                .memo(request.memo())
                .recurring(false)
                .planned(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Expense 엔티티를 수입 응답 DTO로 변환한다.
     *
     * @param expense 저장된 Expense 엔티티
     * @return 수입 등록 응답 DTO
     */
    public static IncomeResponse toIncomeResponse(Expense expense) {
        return new IncomeResponse(
                expense.getId(),
                expense.getAmount(),
                expense.getTitle(),
                expense.getExpenseDate(),
                expense.getCategoryId(),
                expense.getMemo(),
                expense.getCreatedAt());
    }
}
