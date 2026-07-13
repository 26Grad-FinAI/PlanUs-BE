package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 주간/월간 소비 피드백 엔티티. {@code ai_feedbacks} 테이블 매핑.
 *
 * <p>파이프라인 실행 결과를 저장한다. 멱등 upsert 키: {@code (user_id, period_type, period_start, period_end)}.
 * v2에서 추가된 필드:
 * <ul>
 *   <li>{@code feedbackType} — ALERT / POSITIVE / LOW_DATA
 *   <li>{@code payload} — 구조화된 분석 결과 (JSONB)
 *   <li>{@code promptVersion}, {@code logicVersion} — 재현을 위한 버전 기록
 * </ul>
 */
@Entity
@Table(
        name = "ai_feedbacks",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_feedback_period",
                        columnNames = {"user_id", "period_type", "period_start", "period_end"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "year_month")
    private LocalDate yearMonth; // NOT NULL — 해당 월 1일

    @Column(name = "period_type")
    private String periodType; // WEEKLY/MONTHLY

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "overall_opinion", columnDefinition = "TEXT")
    private String feedbackText; // 기준 스키마 컬럼명

    private String confidence; // HIGH/MEDIUM/LOW

    @Column(name = "category_id")
    private Integer categoryId; // 권고 카테고리(값 1~11)

    @Column(name = "advice_type")
    private String adviceType;

    @Column(name = "feedback_type", length = 20)
    private String feedbackType;

    @Column(name = "payload", columnDefinition = "JSONB")
    private String payload;

    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    @Column(name = "logic_version", length = 20)
    private String logicVersion;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 기존 피드백을 최신 분석 결과로 갱신한다 (멱등 upsert용).
     *
     * @param feedbackText 새 피드백 텍스트
     * @param confidence   새 신뢰도
     * @param categoryId   새 권고 카테고리
     * @param adviceType   새 권고 유형
     * @param updatedAt    갱신 시각
     */
    public void update(
            String feedbackText, String confidence, Integer categoryId, String adviceType, LocalDateTime updatedAt) {
        this.feedbackText = feedbackText;
        this.confidence = confidence;
        this.categoryId = categoryId;
        this.adviceType = adviceType;
        this.createdAt = updatedAt;
    }
}
