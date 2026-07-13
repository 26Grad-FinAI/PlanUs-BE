package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 월말 검증 엔티티. {@code month_end_verification} 테이블 매핑.
 *
 * <p>매월 1일 03:00 배치에서 전월 주간 피드백의 예측 총지출과 실제 총지출을 대조하여
 * MAPE(Mean Absolute Percentage Error)를 산출하고, 제안 후 카테고리별 지출 변화를 추적한다.
 * 유니크 제약: {@code (user_id, year_month)}.
 */
@Entity
@Table(
        name = "month_end_verification",
        indexes = @Index(name = "uk_mev_user_month", columnList = "user_id, year_month", unique = true))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthEndVerification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "year_month", nullable = false)
    private LocalDate yearMonth;

    @Column(name = "predicted_total", nullable = false)
    private long predictedTotal;

    @Column(name = "actual_total", nullable = false)
    private long actualTotal;

    @Column(nullable = false)
    private double mape;

    @Column(name = "post_suggestion_change", columnDefinition = "JSONB")
    private String postSuggestionChange;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
