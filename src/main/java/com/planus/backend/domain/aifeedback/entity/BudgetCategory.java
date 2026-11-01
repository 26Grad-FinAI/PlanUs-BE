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

    @Column(name = "ai_amount")
    private Long aiAmount;

    @Column(name = "user_modified")
    @Builder.Default
    private Boolean userModified = false;

    /** 카테고리 예산에 금액을 추가한다. (수입 등록 시 사용) */
    public void addAmount(long amount) {
        this.amount = Math.addExact(this.amount, amount);
    }

    /** 카테고리 금액을 수정하고 사용자 수정 플래그를 설정한다. */
    public void modifyAmount(long newAmount) {
        this.amount = newAmount;
        this.userModified = true;
    }

    /** AI 제안 금액을 갱신한다. */
    public void updateAiAmount(long aiAmount) {
        this.aiAmount = aiAmount;
    }

    /** AI 제안 금액을 반환한다. null이면 현재 amount로 폴백한다. */
    public long getEffectiveAiAmount() {
        return aiAmount != null ? aiAmount : amount;
    }
}
