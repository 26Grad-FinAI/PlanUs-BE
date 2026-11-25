package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자 피드백 반응(유용/비유용·거부 사유) 엔티티. user_reactions 테이블 매핑. */
@Entity
@Table(name = "user_reactions", indexes = @Index(name = "idx_ur_user_id", columnList = "user_id"))
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
    private Integer categoryId; // 권고됐던 카테고리(조회 편의)

    /** 반응을 남긴(마지막으로 바꾼) 시각. 거절 반응의 만료 판단에 쓴다. */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 반응이 아직 효력이 있는지. 남긴 지 {@code expiryMonths}개월이 지나면 만료된다. 시각을 모르는 이전 데이터는 효력이 있는 것으로 본다.
     */
    public boolean isActiveAt(LocalDateTime now, int expiryMonths) {
        return createdAt == null || !createdAt.plusMonths(expiryMonths).isBefore(now);
    }

    /** 같은 피드백·카테고리에 다시 반응하면 덮어쓴다. */
    public void change(Reaction reaction, RejectionReason rejectionReason, LocalDateTime at) {
        this.reaction = reaction;
        this.rejectionReason = rejectionReason;
        this.createdAt = at;
    }
}
