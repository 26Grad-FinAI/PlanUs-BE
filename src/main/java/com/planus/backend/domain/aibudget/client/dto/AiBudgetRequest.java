package com.planus.backend.domain.aibudget.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** AI 예산 산출 서버 요청 DTO. */
public record AiBudgetRequest(
        @JsonProperty("user_id") String userId,
        Map<String, Object> profile,
        @JsonProperty("자기소개서") String selfIntroduction,
        @JsonProperty("소비내역") List<Map<String, Object>> expenseHistory) {}
