package com.planus.backend.domain.aibudget.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;
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
    private Map<String, Object> observation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> persona;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> strategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_context", columnDefinition = "jsonb")
    private Map<String, Object> userContext;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 분석 데이터를 갱신한다. */
    public void updateAnalysis(
            Map<String, Object> observation,
            Map<String, Object> persona,
            Map<String, Object> strategy,
            Map<String, Object> userContext) {
        this.observation = observation;
        this.persona = persona;
        this.strategy = strategy;
        this.userContext = userContext;
    }
}
