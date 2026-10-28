package com.planus.backend.domain.aibudget.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** AI 예산 산출 서버 응답 DTO. 실제 서버 스펙에 맞춘 전체 구조. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiBudgetResponse(
        @JsonProperty("_meta") Meta meta,
        Observation observation,
        @JsonProperty("user_context") UserContext userContext,
        Strategy strategy,
        Budget budget,
        Persona persona) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(@JsonProperty("user_id") String userId, @JsonProperty("소요ms") long elapsedMs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Observation(
            @JsonProperty("cold_start") boolean coldStart,
            @JsonProperty("유효월수") int validMonths,
            @JsonProperty("총소비") long totalSpending,
            @JsonProperty("카테고리") Map<String, Long> categories,
            @JsonProperty("또래") Map<String, Long> peer,
            @JsonProperty("또래단계") int peerLevel,
            @JsonProperty("또래인원") int peerCount,
            @JsonProperty("behavior_metrics") Map<String, Map<String, Object>> behaviorMetrics) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserContext(
            List<Situation> situation, @JsonProperty("self_report") List<String> selfReport, List<Goal> goals) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Situation(String fact, String since, List<String> affects) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Goal(
                String label,
                @JsonProperty("target_amount") Long targetAmount,
                @JsonProperty("horizon_months") Integer horizonMonths) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Strategy(
            String priority,
            List<Adjustment> adjustments,
            @JsonProperty("protected") List<Protected> protectedCategories,
            String notes) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Adjustment(
                String category,
                String direction,
                String strength,
                @JsonProperty("cap_ratio") double capRatio,
                String rationale) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Protected(String category, @JsonProperty("floor_amount") long floorAmount, String reason) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Budget(List<Allocation> allocations, long savings) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Allocation(String category, long amount, long delta, String reason) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Persona(
            @JsonProperty("기본") String base,
            @JsonProperty("behavior_axes") Map<String, String> behaviorAxes,
            String behavior,
            @JsonProperty("behavior_evidence") List<String> behaviorEvidence,
            Display display,
            @JsonProperty("또래") Map<String, Object> peerInfo) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Display(
                String title,
                String summary,
                @JsonProperty("highlight_category") String highlightCategory,
                @JsonProperty("표시명") String displayName) {}
    }
}
