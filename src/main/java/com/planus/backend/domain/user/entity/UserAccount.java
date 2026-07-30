package com.planus.backend.domain.user.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자 계정 엔티티. user 테이블 매핑. 소득·고정지출·저축목표 등 재무 정보 포함. */
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

    private String email;
    private String password;
    private String nickname;

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

    @Column(name = "refresh_token")
    private String refreshToken;

    /** 가용예산 = 소득 − 고정지출 − 저축목표. */
    public long availableBudget() {
        return monthlyIncome - monthlyFixedExpenses - monthlySavingsGoal;
    }

    /** 재무 프로필(소득, 나이) 입력 완료 여부. */
    public boolean isProfileCompleted() {
        return age != null && monthlyIncome > 0;
    }

    public void updateRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
