package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 버전 관리 예산 스냅샷 엔티티. {@code budget_snapshot} 테이블 매핑.
 *
 * <p>월초에 사용자 소득·고정지출·절약 목표로부터 산출된 예산을 저장한다.
 * 월중 수정 시 {@code version}이 증가하며, 주간 파이프라인은 실행 시점 최신 버전을 참조한다.
 * 유니크 제약: {@code (user_id, year_month, version)}.
 *
 * @see BudgetSnapshotCategory 카테고리별 예산 배분
 */
@Entity
@Table(
        name = "budget_snapshot",
        indexes = @Index(name = "uk_budget_snapshot_ver", columnList = "user_id, year_month, version", unique = true))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BudgetSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "year_month", nullable = false)
    private LocalDate yearMonth;

    @Column(nullable = false)
    @Builder.Default
    private int version = 1;

    @Column(name = "total_budget", nullable = false)
    private long totalBudget;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
