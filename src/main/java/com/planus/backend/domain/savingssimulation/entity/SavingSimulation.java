package com.planus.backend.domain.savingssimulation.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 절약 시뮬레이션 적용 이력 엔티티. saving_simulations 테이블 매핑. */
@Entity
@Table(
        name = "saving_simulations",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_saving_sim_user_feedback",
                        columnNames = {"user_id", "feedback_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavingSimulation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "feedback_id")
    private Long feedbackId;

    @Column(name = "year_month", nullable = false)
    private LocalDate yearMonth;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "is_applied")
    @Builder.Default
    private Boolean isApplied = false;

    @Column(name = "total_saving_amount")
    private Long totalSavingAmount;

    @Column(name = "original_months")
    private Integer originalMonths;

    @Column(name = "reduced_months")
    private Integer reducedMonths;

    @Column(name = "ai_raw_result", columnDefinition = "JSONB")
    private String aiRawResult;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "simulation", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SavingSimulationItem> items = new ArrayList<>();

    public void addItem(SavingSimulationItem item) {
        items.add(item);
        item.setSimulation(this);
    }
}
