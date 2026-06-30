package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자 피드백 반응(유용/비유용·거부 사유) 엔티티. user_reactions 테이블 매핑. */
@Entity
@Table(name = "user_reactions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserReaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "feedback_id")
    private Long feedbackId;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    private Reaction reaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason")
    private RejectionReason rejectionReason;

    @Column(name = "category_id")
    private Integer categoryId;              // 권고됐던 카테고리(조회 편의)
}
