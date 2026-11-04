package com.planus.backend.domain.savingssimulation.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 절약 시뮬레이션 항목 엔티티. saving_simulation_items 테이블 매핑. */
@Entity
@Table(name = "saving_simulation_items", indexes = @Index(name = "idx_ssi_simulation_id", columnList = "simulation_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavingSimulationItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "simulation_id", nullable = false)
    @Setter
    private SavingSimulation simulation;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "saving_amount")
    private Long savingAmount;

    @Column(name = "annual_saving")
    private Long annualSaving;

    @Column(name = "suggestion")
    private String suggestion;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
