package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [3.5] 메모 질문 큐 엔티티. {@code memo_question_queue} 테이블 매핑.
 *
 * <p>HIGH 이상치가 탐지되었으나 감정태그/메모가 없는 거래에 대해
 * 사용자에게 원인을 질문하기 위한 지연 질문 큐.
 * 상태 흐름: {@code PENDING} → {@code ANSWERED} / {@code EXPIRED}.
 * 주당 질문 수 상한은 {@code weeklyQuestionCap} 설정으로 제어한다.
 */
@Entity
@Table(
        name = "memo_question_queue",
        indexes = {
            @Index(name = "idx_mqq_user_status", columnList = "user_id, status"),
            @Index(name = "idx_mqq_user_week_status", columnList = "user_id, question_week, status")
        })
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoQuestionQueue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expense_id", nullable = false)
    private Long expenseId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "anomaly_magnitude", nullable = false)
    private double anomalyMagnitude;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "question_week", nullable = false)
    private LocalDate questionWeek;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 상태를 {@code ANSWERED}로 전환하고 {@code answeredAt}을 현재 시각으로 설정한다. */
    public void markAnswered() {
        this.status = "ANSWERED";
        this.answeredAt = LocalDateTime.now();
    }
}
