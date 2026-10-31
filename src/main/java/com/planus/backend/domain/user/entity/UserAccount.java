package com.planus.backend.domain.user.entity;

import com.planus.backend.domain.user.dto.FixedExpenseItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 사용자 계정 엔티티. user 테이블 매핑. 소득·고정지출·저축목표 등 재무 정보 포함. */
@Entity
@Table(name = "user")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    @Column(nullable = true)
    private String password;

    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AuthProvider provider = AuthProvider.LOCAL;

    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "monthly_income")
    private long monthlyIncome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monthly_fixed_expenses", columnDefinition = "jsonb")
    private List<FixedExpenseItem> monthlyFixedExpenses;

    @Column(name = "monthly_savings_goal")
    private long monthlySavingsGoal;

    @Column(name = "employment_status")
    private Boolean employmentStatus;

    private Integer age;
    private String gender;
    private String residence;

    @Column(name = "home_ownership")
    private Boolean homeOwnership;

    @Column(name = "self_introduction", columnDefinition = "TEXT")
    private String selfIntroduction;

    @Column(name = "fcm_token", length = 512)
    private String fcmToken;

    @Column(name = "refresh_token")
    private String refreshToken;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 고정지출 합계를 반환한다. */
    public long totalFixedExpenses() {
        if (monthlyFixedExpenses == null || monthlyFixedExpenses.isEmpty()) {
            return 0;
        }
        return monthlyFixedExpenses.stream().mapToLong(FixedExpenseItem::amount).sum();
    }

    /** 가용예산 = 소득 − 고정지출 합계 − 저축목표. */
    public long availableBudget() {
        return monthlyIncome - totalFixedExpenses() - monthlySavingsGoal;
    }

    /** 프로필 입력 완료 여부. 필수 필드가 모두 채워진 경우에만 완료로 판단한다. */
    public boolean isProfileCompleted() {
        return age != null
                && monthlyIncome > 0
                && gender != null
                && residence != null
                && employmentStatus != null
                && homeOwnership != null;
    }

    /** 사용자 프로필 정보를 최초 저장하거나 갱신한다. */
    public void updateProfile(
            Integer age,
            String gender,
            String residence,
            long monthlyIncome,
            List<FixedExpenseItem> monthlyFixedExpenses,
            long monthlySavingsGoal,
            Boolean employmentStatus,
            Boolean homeOwnership,
            String selfIntroduction) {
        this.age = age;
        this.gender = gender;
        this.residence = residence;
        this.monthlyIncome = monthlyIncome;
        this.monthlyFixedExpenses = monthlyFixedExpenses;
        this.monthlySavingsGoal = monthlySavingsGoal;
        this.employmentStatus = employmentStatus;
        this.homeOwnership = homeOwnership;
        this.selfIntroduction = selfIntroduction;
    }

    /** 비밀번호를 변경한다. */
    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    /** FCM 디바이스 토큰을 갱신한다. */
    public void updateFcmToken(String fcmToken) {
        this.fcmToken = fcmToken;
    }

    /** 로그인·회원가입 시 발급된 리프레시 토큰을 저장한다. */
    public void updateRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
