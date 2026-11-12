package com.planus.backend.domain.aifeedback.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.planus.backend.domain.aifeedback.cause.CauseSignals;
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
        FeedbackContext ctx =
                contextBuilder().feedbackType(FeedbackType.LOW_DATA).build();

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
                .thenReturn("이번 달 600,000원 쓸 것 같아요.") // 불일치
                .thenReturn("이번 달 600,000원 쓸 것 같아요.") // 불일치
                .thenReturn("이번 달 800,000원 쓸 것 같아요."); // 일치

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

            FeedbackContext ctx =
                    contextBuilder().feedbackType(FeedbackType.POSITIVE).build();

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

        FeedbackContext ctx = contextBuilder()
                .feedbackType(FeedbackType.POSITIVE)
                .bandWidth(BandWidth.WIDE)
                .build();

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

    // ── 만원 단위 표현 허용 (#111, exp0 실측 사례 기반) ──

    @Nested
    @DisplayName("만원 단위 표현 허용 — 내림·올림 + 10% 이내")
    class ManWonBracketTest {

        @Test
        @DisplayName("만원 단위 내림 + 수식어: 117,266원 → '11만 원 넘게' 통과 (exp0 오탐 최다 사례)")
        void floorWithQualifier_passes() {
            assertThat(renderer.isAllowedExpression(110_000, 117_266)).isTrue(); // 6.2%
            assertThat(renderer.isAllowedExpression(100_000, 105_500)).isTrue(); // 5.2%
            assertThat(renderer.isAllowedExpression(80_000, 85_219)).isTrue(); // 6.1%
        }

        @Test
        @DisplayName("만원 단위 올림: 117,266원 → '12만 원' 통과")
        void ceil_passes() {
            assertThat(renderer.isAllowedExpression(120_000, 117_266)).isTrue();
            assertThat(renderer.isAllowedExpression(90_000, 85_219)).isTrue();
        }

        @Test
        @DisplayName("만원 단위를 벗어나면 거절: 117,266원 → '10만 원' / '13만 원'")
        void outsideBracket_rejected() {
            assertThat(renderer.isAllowedExpression(100_000, 117_266)).isFalse();
            assertThat(renderer.isAllowedExpression(130_000, 117_266)).isFalse();
        }

        @Test
        @DisplayName("만원 단위 안이어도 10% 초과면 거절: 14,400원 → '2만 원'(39%), 59,000원 → '5만 원'(15%)")
        void insideBracketButTooFar_rejected() {
            assertThat(renderer.isAllowedExpression(20_000, 14_400)).isFalse();
            assertThat(renderer.isAllowedExpression(50_000, 59_000)).isFalse();
        }

        @Test
        @DisplayName("[알려진 느슨함] 기존 반올림 규칙은 작은 금액에서 큰 차이를 허용: 14,400원 → '1만 원'(30%) 통과")
        void knownLooseness_smallAmountRounding() {
            // 만원 단위 반올림값(10,000)과 일치하므로 기존 규칙으로 통과한다. 이번 변경 전부터의 동작이며,
            // 통과 응답 누락 조사에서 문제가 확인되면 반올림 규칙에도 상대 오차 상한을 두는 것을 검토한다.
            assertThat(renderer.isAllowedExpression(10_000, 14_400)).isTrue();
        }

        @Test
        @DisplayName("원본이 만원 배수면 올림 없음: 110,000원 → '12만 원' 거절")
        void exactManWon_noCeil() {
            assertThat(renderer.isAllowedExpression(110_000, 110_000)).isTrue();
            assertThat(renderer.isAllowedExpression(120_000, 110_000)).isFalse();
        }

        @Test
        @DisplayName("1만원 미만은 기존 5% 규칙만 적용")
        void belowManWon_onlyTolerance() {
            assertThat(renderer.isAllowedExpression(5_000, 5_100)).isTrue();
            assertThat(renderer.isAllowedExpression(10_000, 5_100)).isFalse();
        }

        @Test
        @DisplayName("기존 규칙 유지: 5% 이내 / 만원 반올림값 5% 이내")
        void existingRulesKept() {
            assertThat(renderer.isAllowedExpression(800_000, 790_000)).isTrue(); // 1.3%
            assertThat(renderer.isAllowedExpression(80_000, 84_000)).isTrue(); // 반올림 8만
            assertThat(renderer.isAllowedExpression(900_000, 800_000)).isFalse(); // 12.5%
        }

        @Test
        @DisplayName("exp0 진짜 오류 회귀: 절약 액션에 없는 '10만 원 이상 절약'은 여전히 거절")
        void fabricatedSavings_stillRejected() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .predictedMonthEndWon(1_142_719)
                    .savingsImpactWon(-142_719)
                    .actions(List.of(
                            new ActionSummary("기타", 73_932, null, 0.5), new ActionSummary("식료품", 31_175, null, 0.5)))
                    .build();

            assertThat(renderer.verifyNumbers("외식을 줄이면 월말까지 10만 원 이상 절약할 수 있어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyNumbers("기타에서 7만 원 넘게 줄이면 목표에 가까워져요.", ctx))
                    .isTrue();
        }
    }

    // ── "N만 원대" 범위 표현 (#111, exp2 23주차 오탐 사례) ──

    @Nested
    @DisplayName("'N만 원대' 범위 표현 — 원본이 그 만원 구간이면 허용")
    class ManWonRangeTest {

        private FeedbackContext ctxWith(long... amounts) {
            List<FeedbackRenderer.TransactionHighlight> hs = java.util.Arrays.stream(amounts)
                    .mapToObj(a -> new FeedbackRenderer.TransactionHighlight(
                            java.time.LocalDate.of(2026, 7, 10), "기타", a, null, null))
                    .toList();
            return contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .weekHighlights(hs)
                    .build();
        }

        @Test
        @DisplayName("66,700원 → '6만 원대', 45,900원 → '4만 원대' 통과 (10% 초과여도 구간 안이면 사실)")
        void rangeExpression_passes() {
            assertThat(renderer.verifyNumbers("세일 충동구매로 6만 원대가 한 번에 나갔어요.", ctxWith(66_700)))
                    .isTrue();
            assertThat(renderer.verifyNumbers("스트레스 해소로 쓴 4만원대 식대도 있었어요.", ctxWith(45_900)))
                    .isTrue();
        }

        @Test
        @DisplayName("구간 밖이면 거절: 45,900원 → '5만 원대' (만원 단위 올림 근사로는 통과하지 않음)")
        void outsideRange_rejected() {
            assertThat(renderer.verifyNumbers("5만 원대 식대가 있었어요.", ctxWith(45_900)))
                    .isFalse();
        }

        @Test
        @DisplayName("'원대'가 아닌 근사는 기존 10% 규칙: 45,900원 → '4만 원 정도' 거절 (12.9%)")
        void approximateNotRange_rejected() {
            assertThat(renderer.verifyNumbers("4만 원 정도 식대가 있었어요.", ctxWith(45_900)))
                    .isFalse();
        }

        @Test
        @DisplayName("'원 대비'·'원대비'는 범위 표현이 아님")
        void comparisonNotRange() {
            assertThat(renderer.extractMoneyMentions("100만원대비 4만원대 지출"))
                    .containsExactly(
                            new FeedbackRenderer.MoneyMention(1_000_000, false),
                            new FeedbackRenderer.MoneyMention(40_000, true));
        }

        @Test
        @DisplayName("이전 규칙(만원 단위 허용 끔)에서는 범위 표현도 허용하지 않음 — 측정 도구의 '새 규칙으로 통과' 구분 유지")
        void legacyRule_noRange() {
            assertThat(renderer.verifyNumbers("6만 원대가 나갔어요.", ctxWith(66_700), false))
                    .isFalse();
        }
    }

    // ── 배수 표기 (#111, exp1: 한 건 배수를 카테고리 전체에 붙인 표현이 주간 응답의 51%) ──

    @Nested
    @DisplayName("배수 표기 — 무엇의 배수인지 프롬프트에 명시")
    class MultiplierNotationTest {

        private String capturedUserPrompt(FeedbackContext ctx) {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString())).thenReturn("기록을 잘 남기고 계세요.");
            renderer.render(ctx);
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client).complete(anyString(), captor.capture());
            return captor.getValue();
        }

        private String capturedSystemPrompt(FeedbackContext ctx) {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString())).thenReturn("기록을 잘 남기고 계세요.");
            renderer.render(ctx);
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client).complete(captor.capture(), anyString());
            return captor.getValue();
        }

        @Test
        @DisplayName("단발 이상치: 한 건 금액·평소 한 건 금액·이번 주 합계를 구분해 표기 (exp1 39주차 사례)")
        void singleTransaction_basisSpelledOut() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .anomaly(
                            "기타", 6.83, 117_830, new FeedbackRenderer.AnomalyBasis(true, 94_000, 13_759, "오픈뱅킹출금 토스페이"))
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("- 기타: 한 건 \"오픈뱅킹출금 토스페이\" 94,000원이 평소 기타 한 건 금액(13,759원)의 6.8배 [한 건 배수]"
                            + " / 기타 이번 주 합계: 117,830원")
                    .doesNotContain("평소 대비 약");
        }

        @Test
        @DisplayName("추세 이상치: 최근 4주 주당 평균 vs 그 전 4주 주당 평균으로 표기")
        void trend_basisSpelledOut() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .anomaly("식대", 1.8, 210_000, new FeedbackRenderer.AnomalyBasis(false, 180_000, 100_000, null))
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("- 식대: 최근 4주 주당 평균 180,000원이 그 전 4주 주당 평균(100,000원)의 1.8배 [주간 금액 배수]");
        }

        @Test
        @DisplayName("기준 정보가 없으면(과거 거래 없음) 기존 표기 유지")
        void noBasis_legacyNotation() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .anomaly("기타", 6.83, 117_830, null)
                    .build();

            assertThat(capturedUserPrompt(ctx)).contains("- 기타: 평소 대비 약 6.8배 / 기타 이번 주 합계: 117,830원");
        }

        @Test
        @DisplayName("초과 카테고리 배율은 금액 기준임을 명시 (exp1 33주차 '외식 횟수가 1.7배' 사례)")
        void selfRatio_amountBasisSpelledOut() {
            CauseSignals cs = new CauseSignals(
                    1.7, "FREQUENCY", 0.8, 0.2, java.util.Map.of(), java.util.Map.of(), null, List.of(), false);
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(new CategoryOverspend("식대", 191_731, 139_940, cs)))
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("(이번 주 금액이 평소 주당 금액의 1.7배) — 원인: 횟수 증가")
                    .doesNotContain("과거 대비");
        }

        @Test
        @DisplayName("시스템 규칙: 배수의 대상 명시 + 소수 첫째 자리까지 그대로")
        void systemPrompt_multiplierRules() {
            FeedbackContext ctx =
                    contextBuilder().feedbackType(FeedbackType.ALERT).build();

            assertThat(capturedSystemPrompt(ctx))
                    .contains("'한 건' 배수는 그 거래에만 붙이고")
                    .contains("배수는 제공된 값을 소수 첫째 자리까지 그대로 쓴다");
        }

        @Test
        @DisplayName("프롬프트에 추가된 기준 금액(평소 한 건 금액)을 LLM이 언급해도 숫자 검증 통과")
        void basisAmounts_allowedInVerification() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .anomaly(
                            "기타", 6.83, 117_830, new FeedbackRenderer.AnomalyBasis(true, 94_000, 13_759, "오픈뱅킹출금 토스페이"))
                    .build();

            assertThat(renderer.verifyNumbers("토스페이 94,000원 한 건이 평소 한 건(13,759원)의 6.8배였어요.", ctx))
                    .isTrue();
        }
    }

    // ── 측정 도구(LlmRenderMeter) 판정 검증 (#111) ──

    @Nested
    @DisplayName("LlmRenderMeter — 실제 렌더러 동작 기준 판정")
    class LlmRenderMeterTest {

        private static final String MATCH = "예상 지출 800,000원이에요.";
        private static final String MISMATCH = "예상 지출 900,000원이에요."; // 12.5% 오차 → 거절

        private final LlmRenderMeter meter = new LlmRenderMeter();

        /** 응답을 순서대로 돌려주는 가짜 LLM. RuntimeException 원소는 던진다. */
        private LlmClient scripted(Object... answers) {
            java.util.Iterator<Object> it = List.of(answers).iterator();
            return (system, user) -> {
                Object next = it.next();
                if (next instanceof RuntimeException e) throw e;
                return (String) next;
            };
        }

        private void renderOnce(LlmClient stub, String label) {
            when(llmProvider.getIfAvailable()).thenReturn(meter.wrap(stub));
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .predictedMonthEndWon(800_000)
                    .build();
            meter.begin();
            Rendered result = renderer.render(ctx);
            meter.end(result.text(), label);
        }

        @Test
        @DisplayName("1차 응답이 검증 통과 → 1차 통과, 거절 0")
        void firstAttemptPass() {
            renderOnce(scripted(MATCH), "case");

            assertThat(meter.passedAt(1)).isEqualTo(1);
            assertThat(meter.totalCalls()).isEqualTo(1);
            assertThat(meter.rejectedCount()).isZero();
            assertThat(meter.fallbackVerify() + meter.fallbackError()).isZero();
        }

        @Test
        @DisplayName("불일치 2회 후 3차 통과 → 3차 통과, 거절 2")
        void thirdAttemptPass() {
            renderOnce(scripted(MISMATCH, MISMATCH, MATCH), "case");

            assertThat(meter.passedAt(3)).isEqualTo(1);
            assertThat(meter.passedAt(1)).isZero();
            assertThat(meter.totalCalls()).isEqualTo(3);
            assertThat(meter.rejectedCount()).isEqualTo(2);
            assertThat(meter.responses()).isEqualTo(3);
        }

        @Test
        @DisplayName("3회 모두 불일치 → 검증 실패 폴백, 거절 3")
        void allMismatch_verifyFallback() {
            renderOnce(scripted(MISMATCH, MISMATCH, MISMATCH), "case");

            assertThat(meter.fallbackVerify()).isEqualTo(1);
            assertThat(meter.fallbackError()).isZero();
            assertThat(meter.llmTextUsed()).isZero();
            assertThat(meter.rejectedCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("LLM 예외 → 오류 폴백, 응답·거절 0")
        void exception_errorFallback() {
            renderOnce(scripted(new RuntimeException("timeout")), "case");

            assertThat(meter.fallbackError()).isEqualTo(1);
            assertThat(meter.fallbackVerify()).isZero();
            assertThat(meter.totalCalls()).isEqualTo(1);
            assertThat(meter.responses()).isZero();
            assertThat(meter.rejectedCount()).isZero();
        }

        @Test
        @DisplayName("빈 응답 → 재시도 없이 오류 폴백, 빈 응답은 거절로 세지 않음")
        void blankResponse_errorFallback() {
            renderOnce(scripted(""), "case");

            assertThat(meter.fallbackError()).isEqualTo(1);
            assertThat(meter.totalCalls()).isEqualTo(1);
            assertThat(meter.rejectedCount()).isZero();
        }

        @Test
        @DisplayName("1차 불일치 후 예외 → 오류 폴백, 1차 응답은 거절로 집계")
        void mismatchThenException_errorFallbackWithOneRejected() {
            renderOnce(scripted(MISMATCH, new RuntimeException("timeout")), "case");

            assertThat(meter.fallbackError()).isEqualTo(1);
            assertThat(meter.totalCalls()).isEqualTo(2);
            assertThat(meter.rejectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("반복 렌더 라벨에 반복 번호와 최종 결과가 붙는다")
        void rejectedLineContainsRepAndOutcome() {
            when(llmProvider.getIfAvailable()).thenReturn(meter.wrap(scripted(MISMATCH, MATCH)));
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .predictedMonthEndWon(800_000)
                    .build();
            meter.begin();
            Rendered result = renderer.render(ctx);
            meter.end(result.text(), "제18주차", 2);

            assertThat(meter.rejectedLines()).containsExactly("[제18주차 r2 / 시도 1 | 최종: 2차 통과] " + MISMATCH);
            assertThat(meter.acceptedLines()).containsExactly("[제18주차 r2 / 시도 2] " + MATCH);
        }

        @Test
        @DisplayName("이전 규칙으로는 거절되고 새 규칙(만원 단위)으로만 통과한 응답에 표시가 붙는다")
        void passedOnlyByNewRule_tagged() {
            String floorExpr = "예상 지출이 11만 원 넘게 나올 것 같아요."; // 117,266원의 만원 단위 내림 (6.2%)
            String exact = "예상 지출이 117,266원 나올 것 같아요.";
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .predictedMonthEndWon(117_266)
                    .build();

            for (String answer : List.of(floorExpr, exact)) {
                when(llmProvider.getIfAvailable()).thenReturn(meter.wrap(scripted(answer)));
                meter.begin(LlmRenderMeter.legacyRule(renderer, ctx));
                Rendered result = renderer.render(ctx);
                meter.end(result.text(), "case");
            }

            assertThat(meter.passedAt(1)).isEqualTo(2);
            assertThat(meter.passedOnlyByNewRule()).isEqualTo(1);
            assertThat(meter.acceptedLines())
                    .containsExactly("[case / 시도 1 | 새 규칙으로 통과] " + floorExpr, "[case / 시도 1] " + exact);
        }

        @Test
        @DisplayName("템플릿 폴백 문장은 통과 응답으로 기록하지 않는다")
        void fallbackNotRecordedAsAccepted() {
            renderOnce(scripted(MISMATCH, MISMATCH, MISMATCH), "case");

            assertThat(meter.acceptedLines()).isEmpty();
        }

        @Test
        @DisplayName("여러 렌더 누적 집계")
        void accumulatesAcrossRenders() {
            renderOnce(scripted(MATCH), "r1");
            renderOnce(scripted(MISMATCH, MATCH), "r2");
            renderOnce(scripted(MISMATCH, MISMATCH, MISMATCH), "r3");

            assertThat(meter.renders()).isEqualTo(3);
            assertThat(meter.totalCalls()).isEqualTo(6);
            assertThat(meter.passedAt(1)).isEqualTo(1);
            assertThat(meter.passedAt(2)).isEqualTo(1);
            assertThat(meter.fallbackVerify()).isEqualTo(1);
            assertThat(meter.rejectedCount()).isEqualTo(4);
        }
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
        private FeedbackRenderer.AnomalyBasis anomalyBasis = null;

        FeedbackContextBuilder anomaly(
                String categoryName, double magnitude, long weeklyAmountWon, FeedbackRenderer.AnomalyBasis basis) {
            this.hasAnomaly = true;
            this.anomalyCategoryName = categoryName;
            this.anomalyMagnitude = magnitude;
            this.anomalyWeeklyAmountWon = weeklyAmountWon;
            this.anomalyConfidence = Confidence.HIGH;
            this.anomalyBasis = basis;
            return this;
        }

        FeedbackContextBuilder weekHighlights(List<FeedbackRenderer.TransactionHighlight> v) {
            this.weekHighlights = v;
            return this;
        }

        FeedbackContextBuilder overspendCategories(List<CategoryOverspend> v) {
            this.overspendCategories = v;
            return this;
        }

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
                    overallConfidence,
                    anomalyBasis);
        }
    }
}
