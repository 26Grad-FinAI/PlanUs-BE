package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 월별 총 예산 엔티티. budget 테이블 매핑. */
@Entity
@Table(
        name = "budget",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_budget_user_month",
                        columnNames = {"user_id", "year_month"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Budget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "year_month")
    private LocalDate yearMonth; // 해당 월 1일

    @Column(name = "total_budget")
    private long totalBudget;

    /** 총 예산에 금액을 추가한다. (수입 등록 시 사용) */
    public void addTotalBudget(long amount) {
        this.totalBudget += amount;
    }
}
