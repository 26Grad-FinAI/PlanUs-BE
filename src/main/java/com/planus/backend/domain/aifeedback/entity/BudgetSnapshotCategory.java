package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 예산 스냅샷의 카테고리별 배분 엔티티. {@code budget_snapshot_category} 테이블 매핑.
 *
 * <p>하나의 {@link BudgetSnapshot}에 대해 카테고리별 예산 금액을 저장한다.
 *
 * @see BudgetSnapshot 상위 스냅샷
 */
@Entity
@Table(
        name = "budget_snapshot_category",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_bsc_snapshot_category",
                        columnNames = {"snapshot_id", "category_id"}),
        indexes = @Index(name = "idx_bsc_snapshot", columnList = "snapshot_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BudgetSnapshotCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false)
    private long amount;
}
