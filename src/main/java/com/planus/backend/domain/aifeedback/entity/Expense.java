package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 거래(수입/지출) 엔티티. expense 테이블 매핑. */
@Entity
@Table(name = "expense", indexes = @Index(name = "idx_expense_user_date", columnList = "user_id, expense_date"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Expense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "category_id")
    private Integer categoryId;

    private String type; // EXPENSE / INCOME
    private long amount; // 원

    @Column(name = "description")
    private String title; // 내역

    @Column(name = "expense_date")
    private LocalDateTime expenseDate;

    private String memo;
    private String emotion; // NECESSARY/SATISFIED/IMPULSE/STRESS_RELIEF/REGRET

    @Column(name = "is_recurring")
    private boolean recurring;

    @Column(name = "is_planned")
    private boolean planned;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 거래 일자(시간 제외)를 반환한다. */
    public LocalDate getDate() {
        return expenseDate.toLocalDate();
    }

    /** 지출 여부를 반환한다. type이 "EXPENSE"이면 true. */
    public boolean isExpense() {
        return "EXPENSE".equalsIgnoreCase(type);
    }

    /** 변동 지출 여부를 반환한다. 지출이면서 반복·예정이 아닌 거래. */
    public boolean isVariable() {
        return isExpense() && !recurring && !planned;
    }

    /** 지출 정보를 수정한다. */
    public void updateExpense(
            long amount,
            String title,
            LocalDateTime expenseDate,
            Integer categoryId,
            String memo,
            String emotion,
            boolean recurring,
            boolean planned) {
        this.amount = amount;
        this.title = title;
        this.expenseDate = expenseDate;
        this.categoryId = categoryId;
        this.memo = memo;
        this.emotion = emotion;
        this.recurring = recurring;
        this.planned = planned;
    }

    /** 수입 정보를 수정한다. */
    public void updateIncome(long amount, String title, LocalDateTime expenseDate, Integer categoryId, String memo) {
        this.amount = amount;
        this.title = title;
        this.expenseDate = expenseDate;
        this.categoryId = categoryId;
        this.memo = memo;
    }
}
