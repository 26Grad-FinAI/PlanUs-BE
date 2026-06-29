package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "expense")
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

    private String type;                 // EXPENSE / INCOME
    private long amount;                 // 원

    @Column(name = "expense_date")
    private LocalDateTime expenseDate;

    private String memo;
    private String emotion;              // NECESSARY/SATISFIED/IMPULSE/STRESS_RELIEF/REGRET

    @Column(name = "is_recurring")
    private boolean recurring;

    @Column(name = "is_planned")
    private boolean planned;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public LocalDate getDate() { return expenseDate.toLocalDate(); }
    public boolean isExpense() { return "EXPENSE".equalsIgnoreCase(type); }
    public boolean isVariable() { return isExpense() && !recurring && !planned; }
}
