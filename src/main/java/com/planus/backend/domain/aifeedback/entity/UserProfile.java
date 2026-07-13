package com.planus.backend.domain.aifeedback.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 누적 해석 프로필 엔티티. {@code user_profile} 테이블 매핑.
 *
 * <p>파이프라인 [8]에서 매주 갱신되며, 다음 주기에 개인화 근거로 사용된다.
 *
 * <ul>
 *   <li>{@code repeatPatterns} — 같은 카테고리 연속 3주+ 초과 시 기록 (JSONB)
 *   <li>{@code sensitiveAreas} — {@code DONT_WANT_REDUCE} 2회+ 거부된 카테고리 ID 목록 (JSONB)
 *   <li>{@code complianceRate} — 최근 12주 중 예산 내 주 비율
 * </ul>
 */
@Entity
@Table(name = "user_profile")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "repeat_patterns", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private String repeatPatterns = "{}";

    @Column(name = "sensitive_areas", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private String sensitiveAreas = "[]";

    @Column(name = "compliance_rate")
    @Builder.Default
    private double complianceRate = 0.0;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * 반복 패턴 JSONB를 갱신하고 {@code updatedAt}을 현재 시각으로 설정한다.
     *
     * @param repeatPatterns 새 반복 패턴 JSON 문자열 (예: {@code {"외식":{"streak":4,"since":"2026-06"}}})
     */
    public void updateRepeatPatterns(String repeatPatterns) {
        this.repeatPatterns = repeatPatterns;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 민감 영역(제안 금지 카테고리) JSONB를 갱신하고 {@code updatedAt}을 현재 시각으로 설정한다.
     *
     * @param sensitiveAreas 새 민감 영역 JSON 배열 문자열 (예: {@code [12]})
     */
    public void updateSensitiveAreas(String sensitiveAreas) {
        this.sensitiveAreas = sensitiveAreas;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 예산 준수율을 갱신하고 {@code updatedAt}을 현재 시각으로 설정한다.
     *
     * @param complianceRate 최근 12주 중 예산 내 주 비율 (0.0 ~ 1.0)
     */
    public void updateComplianceRate(double complianceRate) {
        this.complianceRate = complianceRate;
        this.updatedAt = LocalDateTime.now();
    }
}
