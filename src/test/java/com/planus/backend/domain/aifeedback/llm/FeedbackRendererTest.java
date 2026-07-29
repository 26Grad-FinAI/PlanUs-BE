package com.planus.backend.domain.aifeedback.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.planus.backend.domain.aifeedback.core.BandWidth;
import com.planus.backend.domain.aifeedback.core.Confidence;
import com.planus.backend.domain.aifeedback.core.FeedbackType;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.ActionSummary;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.CategoryOverspend;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.FeedbackContext;
import com.planus.backend.domain.aifeedback.llm.FeedbackRenderer.Rendered;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FeedbackRendererTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<LlmClient> llmProvider = mock(ObjectProvider.class);

    private final FeedbackRenderer renderer = new FeedbackRenderer(llmProvider);

    // ── LOW_DATA ──

    @Test
    @DisplayName("LOW_DATA → LLM 호출 없이 결정적 메시지")
    void render_lowData_deterministicMessage() {
        FeedbackContext ctx = contextBuilder().feedbackType(FeedbackType.LOW_DATA).build();

        Rendered result = renderer.render(ctx);

        assertThat(result.text()).contains("기록이 평소보다 적어서");
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        verifyNoInteractions(llmProvider);
    }

    // ── LLM 호출 ──

    @Test
    @DisplayName("ALERT → LLM 호출")
    void render_alert_callsLlm() {
        LlmClient client = mock(LlmClient.class);
        when(llmProvider.getIfAvailable()).thenReturn(client);
        // LLM이 정확한 금액을 사용하여 검증 통과
        when(client.complete(anyString(), anyString())).thenReturn("이번 달 약 800,000원을 쓸 것 같아요.");

        FeedbackContext ctx = contextBuilder()
                .feedbackType(FeedbackType.ALERT)
                .predictedMonthEndWon(800_000)
                .build();

        Rendered result = renderer.render(ctx);

        assertThat(result.text()).contains("800,000원");
        verify(client).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("처음 2회 숫자 불일치, 3회째 일치 → LLM 텍스트 채택 + complete() 3회 호출")
    void render_retrySucceedsOnThirdAttempt() {
        LlmClient client = mock(LlmClient.class);
        when(llmProvider.getIfAvailable()).thenReturn(client);
        // 불일치 금액(600,000): allowed values(800,000 / 1,000,000 / 200,000)와 모두 5% 이상 오차
        // 일치 금액(800,000): predictedMonthEndWon과 정확히 일치
        when(client.complete(anyString(), anyString()))
                .thenReturn("이번 달 600,000원 쓸 것 같아요.")   // 불일치
                .thenReturn("이번 달 600,000원 쓸 것 같아요.")   // 불일치
                .thenReturn("이번 달 800,000원 쓸 것 같아요.");  // 일치

        FeedbackContext ctx = contextBuilder()
                .feedbackType(FeedbackType.ALERT)
                .predictedMonthEndWon(800_000)
                .build();

        Rendered result = renderer.render(ctx);

        assertThat(result.text()).isEqualTo("이번 달 800,000원 쓸 것 같아요.");
        verify(client, times(3)).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("LLM 예외 → 템플릿 폴백 (결정적 출력)")
    void render_llmFails_templateFallback() {
        LlmClient client = mock(LlmClient.class);
        when(llmProvider.getIfAvailable()).thenReturn(client);
        when(client.complete(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));

        FeedbackContext ctx = alertContext();

        Rendered result = renderer.render(ctx);

        // LLM 없이 렌더링한 결과와 동일해야 함 (결정적 폴백)
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmClient> noLlm = mock(ObjectProvider.class);
        when(noLlm.getIfAvailable()).thenReturn(null);
        FeedbackRenderer noLlmRenderer = new FeedbackRenderer(noLlm);
        Rendered expected = noLlmRenderer.render(ctx);

        assertThat(result.text()).isEqualTo(expected.text());
    }

    // ── 숫자 후검증 ──

    @Nested
    @DisplayName("숫자 후검증")
    class NumberVerificationTest {

        @Test
        @DisplayName("LLM 숫자가 원본과 일치 → 통과")
        void numberMatch_returnsLlmText() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString())).thenReturn("예상 지출 800,000원이에요.");

            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .predictedMonthEndWon(800_000)
                    .build();

            Rendered result = renderer.render(ctx);

            assertThat(result.text()).isEqualTo("예상 지출 800,000원이에요.");
        }

        @Test
        @DisplayName("LLM 숫자 불일치 → 템플릿 폴백")
        void numberMismatch_templateFallback() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            // 800,000 → 900,000 (12.5% 오차)
            when(client.complete(anyString(), anyString())).thenReturn("예상 지출 900,000원이에요.");

            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .predictedMonthEndWon(800_000)
                    .build();

            Rendered result = renderer.render(ctx);

            // 폴백 템플릿 사용 (LLM 텍스트가 아님)
            assertThat(result.text()).doesNotContain("900,000원");
        }

        @Test
        @DisplayName("반올림 허용: '약 8만원' vs 84,000 → 5% 이내 통과")
        void roundedNumber_passes() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            // 84,000 → "약 8만원" (80,000). 오차 4.8% → 통과
            when(client.complete(anyString(), anyString())).thenReturn("약 8만원 줄이면 돼요.");

            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .predictedMonthEndWon(840_000)
                    .actions(List.of(new ActionSummary("외식·숙박", 84_000, "FREQUENCY", 1.0)))
                    .build();

            Rendered result = renderer.render(ctx);

            // 84,000의 만원 반올림 = 80,000 → collectAllowedValues에 포함 → 통과
            assertThat(result.text()).contains("8만원");
        }

        @Test
        @DisplayName("금액 아닌 숫자(퍼센트, 배율)는 검증 대상이 아님")
        void nonMoneyNumbers_ignored() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString())).thenReturn("보통 22% 쓰셨는데 지금은 35%예요. 평소의 2.3배입니다.");

            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.POSITIVE)
                    .build();

            Rendered result = renderer.render(ctx);

            // "원" 접미사 없는 숫자 → 검증 안 함 → 통과
            assertThat(result.text()).contains("22%");
        }
    }

    // ── extractMoneyValues 직접 테스트 ──

    @Test
    @DisplayName("extractMoneyValues: 다양한 금액 형식 파싱")
    void extractMoneyValues_variousFormats() {
        List<Long> values = renderer.extractMoneyValues("12만원과 5,000원, 8.4만원을 썼어요. 35%는 무시.");

        assertThat(values).containsExactly(120_000L, 5_000L, 84_000L);
    }

    // ── 시스템 프롬프트 톤 ──

    @Test
    @DisplayName("WIDE 밴드 → 시스템 프롬프트에 월초 톤 포함")
    void render_wideBand_systemPromptContainsEarlyMonthTone() {
        LlmClient client = mock(LlmClient.class);
        when(llmProvider.getIfAvailable()).thenReturn(client);
        when(client.complete(anyString(), anyString())).thenReturn("기록을 잘 남기고 계세요.");

        FeedbackContext ctx =
                contextBuilder().feedbackType(FeedbackType.POSITIVE).bandWidth(BandWidth.WIDE).build();

        renderer.render(ctx);

        // 시스템 프롬프트 캡처
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).complete(captor.capture(), anyString());
        assertThat(captor.getValue()).contains("월초");
    }

    @Test
    @DisplayName("MEDIUM 밴드 → 시스템 프롬프트에 범위 표현 톤 포함")
    void render_mediumBand_systemPromptContainsRangeTone() {
        LlmClient client = mock(LlmClient.class);
        when(llmProvider.getIfAvailable()).thenReturn(client);
        when(client.complete(anyString(), anyString())).thenReturn("추이를 지켜보세요.");

        FeedbackContext ctx = contextBuilder()
                .feedbackType(FeedbackType.POSITIVE)
                .bandWidth(BandWidth.MEDIUM)
                .build();

        renderer.render(ctx);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).complete(captor.capture(), anyString());
        assertThat(captor.getValue()).contains("범위");
    }

    @Test
    @DisplayName("NARROW 밴드 → 시스템 프롬프트에 확신 있는 톤 포함")
    void render_narrowBand_systemPromptContainsConfidentTone() {
        LlmClient client = mock(LlmClient.class);
        when(llmProvider.getIfAvailable()).thenReturn(client);
        when(client.complete(anyString(), anyString())).thenReturn("기록을 잘 남기고 계세요.");

        FeedbackContext ctx = contextBuilder()
                .feedbackType(FeedbackType.POSITIVE)
                .bandWidth(BandWidth.NARROW)
                .build();

        renderer.render(ctx);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).complete(captor.capture(), anyString());
        assertThat(captor.getValue()).contains("구체적이고 확신 있는");
    }

    // ── 헬퍼 ──

    private FeedbackContext alertContext() {
        return contextBuilder()
                .feedbackType(FeedbackType.ALERT)
                .savingsImpactWon(-100_000)
                .build();
    }

    /** 테스트 빌더 — 기본값으로 유효한 POSITIVE 컨텍스트 생성. */
    private static FeedbackContextBuilder contextBuilder() {
        return new FeedbackContextBuilder();
    }

    /** FeedbackContext는 record라 Builder가 없으므로 테스트용 빌더. */
    private static class FeedbackContextBuilder {
        private long predictedMonthEndWon = 800_000;
        private long availableBudgetWon = 1_000_000;
        private long savingsImpactWon = 200_000;
        private double burnRate = 0.8;
        private com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult pacing = null;
        private boolean hasAnomaly = false;
        private String anomalyCategoryName = null;
        private double anomalyMagnitude = 0;
        private long anomalyWeeklyAmountWon = 0;
        private Confidence anomalyConfidence = Confidence.LOW;
        private String anomalyEmotion = null;
        private List<FeedbackRenderer.AnomalyInfo> additionalHighAnomalies = List.of();
        private FeedbackRenderer.WeekEmotionSummary weekEmotion = null;
        private long weekTotalWon = 0L;
        private long prevWeekTotalWon = 0L;
        private double complianceRate = 0.0;
        private List<CategoryOverspend> overspendCategories = List.of();
        private List<ActionSummary> actions = List.of();
        private List<FeedbackRenderer.TransactionHighlight> weekHighlights = List.of();
        private FeedbackType feedbackType = FeedbackType.POSITIVE;
        private BandWidth bandWidth = null;
        private Confidence overallConfidence = Confidence.HIGH;

        FeedbackContextBuilder predictedMonthEndWon(long v) {
            this.predictedMonthEndWon = v;
            return this;
        }

        FeedbackContextBuilder savingsImpactWon(long v) {
            this.savingsImpactWon = v;
            return this;
        }

        FeedbackContextBuilder feedbackType(FeedbackType v) {
            this.feedbackType = v;
            return this;
        }

        FeedbackContextBuilder bandWidth(BandWidth v) {
            this.bandWidth = v;
            return this;
        }

        FeedbackContextBuilder actions(List<ActionSummary> v) {
            this.actions = v;
            return this;
        }

        FeedbackContextBuilder additionalHighAnomalies(List<FeedbackRenderer.AnomalyInfo> v) {
            this.additionalHighAnomalies = v;
            return this;
        }

        FeedbackContext build() {
            return new FeedbackContext(
                    predictedMonthEndWon,
                    availableBudgetWon,
                    savingsImpactWon,
                    burnRate,
                    pacing,
                    hasAnomaly,
                    anomalyCategoryName,
                    anomalyMagnitude,
                    anomalyWeeklyAmountWon,
                    anomalyConfidence,
                    anomalyEmotion,
                    additionalHighAnomalies,
                    weekEmotion,
                    weekTotalWon,
                    prevWeekTotalWon,
                    complianceRate,
                    overspendCategories,
                    actions,
                    weekHighlights,
                    feedbackType,
                    bandWidth,
                    overallConfidence);
        }
    }
}
