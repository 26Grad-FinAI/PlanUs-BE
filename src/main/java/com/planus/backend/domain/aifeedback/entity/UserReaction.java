package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    private String reaction;                 // HELPFUL/NOT_HELPFUL

    @Column(name = "rejection_reason")
    private String rejectionReason;          // 5-enum or null

    @Column(name = "category_id")
    private Integer categoryId;              // 권고됐던 카테고리(조회 편의)
}
