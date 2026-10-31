package com.planus.backend.domain.aibudget.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** AI 분석 데이터 별도 저장 엔티티. observation, persona, strategy, user_context를 JSONB로 보관한다. */
@Entity
@Table(
        name = "budget_plan_analysis",
        uniqueConstraints = @UniqueConstraint(name = "uk_bpa_budget", columnNames = "budget_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BudgetPlanAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "budget_id", nullable = false)
    private Long budgetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String observation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String persona;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String strategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_context", columnDefinition = "jsonb")
    private String userContext;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 분석 데이터를 갱신한다. */
    public void updateAnalysis(String observation, String persona, String strategy, String userContext) {
        this.observation = observation;
        this.persona = persona;
        this.strategy = strategy;
        this.userContext = userContext;
    }
}
