package com.planus.backend.domain.aibudget.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** AI 예산 산출 요청 상태 추적 엔티티. */
@Entity
@Table(
        name = "budget_plan_request",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_bpr_user_month",
                        columnNames = {"user_id", "year_month"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BudgetPlanRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "year_month", nullable = false)
    private LocalDate yearMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BudgetPlanStatus status = BudgetPlanStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_response", columnDefinition = "jsonb")
    private String aiResponse;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 상태를 IN_PROGRESS로 전환한다. */
    public void markInProgress() {
        this.status = BudgetPlanStatus.IN_PROGRESS;
    }

    /** 성공 완료 처리. AI 응답 JSON을 저장한다. */
    public void markCompleted(String aiResponseJson) {
        this.status = BudgetPlanStatus.COMPLETED;
        this.aiResponse = aiResponseJson;
        this.completedAt = LocalDateTime.now();
    }

    /** 실패 처리. 에러 메시지를 저장한다. */
    public void markFailed(String errorMessage) {
        this.status = BudgetPlanStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }
}
