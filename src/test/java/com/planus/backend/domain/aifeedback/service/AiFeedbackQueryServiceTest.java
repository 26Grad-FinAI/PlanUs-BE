package com.planus.backend.domain.aifeedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.planus.backend.domain.aifeedback.dto.WeeklyFeedbackResponse;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.repository.AiFeedbackRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiFeedbackQueryServiceTest {

    private static final Long USER_ID = 1L;
    private static final LocalDate WEEK_END = LocalDate.of(2026, 11, 15);

    private final AiFeedbackRepository repo = mock(AiFeedbackRepository.class);
    private final AiFeedbackQueryService service = new AiFeedbackQueryService(repo);

    private AiFeedback feedback(LocalDate weekEnd, String text) {
        return AiFeedback.builder()
                .id(1L)
                .userId(USER_ID)
                .periodType("WEEKLY")
                .periodStart(weekEnd.minusDays(6))
                .periodEnd(weekEnd)
                .feedbackText(text)
                .feedbackType("ALERT")
                .confidence("MEDIUM")
                .createdAt(LocalDateTime.of(2026, 11, 16, 9, 0))
                .build();
    }

    @Test
    @DisplayName("weekEnd가 없으면 가장 최근 주 피드백을 반환한다")
    void latest() {
        when(repo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(USER_ID, "WEEKLY"))
                .thenReturn(List.of(feedback(WEEK_END, "최근"), feedback(WEEK_END.minusWeeks(1), "지난주")));

        WeeklyFeedbackResponse result = service.getWeekly(USER_ID, null);

        assertThat(result.exists()).isTrue();
        assertThat(result.feedbackText()).isEqualTo("최근");
        assertThat(result.weekStart()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(result.weekEnd()).isEqualTo(WEEK_END);
        assertThat(result.feedbackType()).isEqualTo("ALERT");
        assertThat(result.confidence()).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("weekEnd를 주면 그 주 피드백을 반환한다")
    void byWeekEnd() {
        when(repo.findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(
                        USER_ID, "WEEKLY", WEEK_END.minusDays(6), WEEK_END))
                .thenReturn(Optional.of(feedback(WEEK_END, "그 주")));

        WeeklyFeedbackResponse result = service.getWeekly(USER_ID, WEEK_END);

        assertThat(result.exists()).isTrue();
        assertThat(result.feedbackText()).isEqualTo("그 주");
    }

    @Test
    @DisplayName("피드백이 없으면 exists=false 빈 응답 (오류 아님)")
    void noneLatest() {
        when(repo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(USER_ID, "WEEKLY"))
                .thenReturn(List.of());

        WeeklyFeedbackResponse result = service.getWeekly(USER_ID, null);

        assertThat(result.exists()).isFalse();
        assertThat(result.feedbackText()).isNull();
        assertThat(result.weekEnd()).isNull();
    }

    @Test
    @DisplayName("해당 주 피드백이 없으면 exists=false")
    void noneByWeekEnd() {
        when(repo.findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(service.getWeekly(USER_ID, WEEK_END).exists()).isFalse();
    }
}
