package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 카테고리별 예산 배분 엔티티. budget_category 테이블 매핑. */
@Entity
@Table(name = "budget_category")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BudgetCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "budget_id")
    private Long budgetId;

    @Column(name = "category_id")
    private Integer categoryId;

    private long amount;

    /** 카테고리 예산에 금액을 추가한다. (수입 등록 시 사용) */
    public void addAmount(long amount) {
        this.amount = Math.addExact(this.amount, amount);
    }
}
