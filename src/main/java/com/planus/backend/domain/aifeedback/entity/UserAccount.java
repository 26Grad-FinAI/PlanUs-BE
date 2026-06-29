package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monthly_income")
    private long monthlyIncome;

    @Column(name = "monthly_fixed_expenses")
    private long monthlyFixedExpenses;

    @Column(name = "monthly_savings_goal")
    private long monthlySavingsGoal;

    @Column(name = "spending_habit")
    private String spendingHabit; // SAVING/BALANCED/SPENDING

    @Column(name = "employment_status")
    private Boolean employmentStatus;

    private Integer age;
    private String gender;
    private String residence;

    /** 가용예산 = 소득 − 고정지출 − 저축목표. */
    public long availableBudget() {
        return monthlyIncome - monthlyFixedExpenses - monthlySavingsGoal;
    }
}
