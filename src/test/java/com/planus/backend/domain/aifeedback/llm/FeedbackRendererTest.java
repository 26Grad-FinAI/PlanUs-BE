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
        @DisplayName("천원 단위 내림·올림 + 10% 이내: 8,557원 → '8천 원'·'9천 원' 통과, '7천 원' 거절 (exp1 24주차)")
        void cheonWonBracket() {
            assertThat(renderer.isAllowedExpression(8_000, 8_557)).isTrue(); // 6.5%
            assertThat(renderer.isAllowedExpression(9_000, 8_557)).isTrue(); // 5.2%
            assertThat(renderer.isAllowedExpression(7_000, 8_557)).isFalse(); // 단위 밖
            assertThat(renderer.isAllowedExpression(2_000, 1_500)).isFalse(); // 33%
        }

        @Test
        @DisplayName("제안 속 예시 금액도 데이터에 없으면 거절 — 사용자 판정 (exp3 7월 결산 '예: 1만 5천원')")
        void exampleAmountInAdvice_rejected() {
            FeedbackContext ctx =
                    contextBuilder().feedbackType(FeedbackType.ALERT).build();

            assertThat(renderer.verifyNumbers("한 번 먹을 때 예산(예: 1만 5천원)을 정해보는 건 어떨까요?", ctx))
                    .isFalse();
        }

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

    // ── 거래처 건수 후검증 (#111, exp8: "카카오페이·토스페이 결제가 7건" 5/40) ──

    @Nested
    @DisplayName("거래처 건수 후검증 — 거래처별 건수와 대조")
    class MerchantCountVerificationTest {

        private FeedbackContext ctx34() {
            java.util.Map<String, Integer> etc = new java.util.LinkedHashMap<>();
            etc.put("카카오페이", 3);
            etc.put("토스페이", 3);
            etc.put("네이버페이결제", 1);
            java.util.Map<String, Integer> fun = new java.util.LinkedHashMap<>();
            fun.put("롯데월드", 4);
            return contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(
                            new CategoryOverspend("기타", 346_563, 141_742, null, 7, etc),
                            new CategoryOverspend("오락문화", 25_691, 116_600, null, 4, fun)))
                    .build();
        }

        @Test
        @DisplayName("여러 거래처 + 카테고리 전체 건수 → 거절 (exp8 34주차 실제 문장)")
        void combinedNamesWithCategoryTotal_rejected() {
            assertThat(renderer.verifyMerchantCounts("기타는 카카오페이·토스페이 결제가 7건 발생했어요.", ctx34()))
                    .isFalse();
            assertThat(renderer.verifyMerchantCounts("기타 항목도 카카오페이·토스페이로 7건이 늘어났는데", ctx34()))
                    .isFalse();
        }

        @Test
        @DisplayName("거래처 건수 합이 맞으면 통과: '합쳐 6건', '카카오페이 3건, 토스페이 3건'")
        void correctCounts_pass() {
            assertThat(renderer.verifyMerchantCounts("토스페이·카카오페이 거래가 합쳐 6건이었어요.", ctx34()))
                    .isTrue();
            assertThat(renderer.verifyMerchantCounts("기타가 14만 원(카카오페이 3건, 토스페이 3건 등)이었어요.", ctx34()))
                    .isTrue();
            assertThat(renderer.verifyMerchantCounts("네이버페이 1건도 있었어요.", ctx34()))
                    .isTrue();
        }

        @Test
        @DisplayName("'N건 중 M건'은 뒤 숫자를 거래처 건수로 본다 (#122 실험 4, 26주차 '토스페이·카카오페이 출금이 12건 중 10건' 오탐)")
        void partOfCount() {
            assertThat(renderer.verifyMerchantCounts("카카오페이·토스페이 결제가 7건 중 6건이었어요.", ctx34()))
                    .isTrue();
            assertThat(renderer.verifyMerchantCounts("카카오페이·토스페이 결제가 7건 중 5건이었어요.", ctx34()))
                    .isFalse();
            // '각각' — 이름마다 (#122 실험 9, 27주차 "카카오페이, 네이버페이 충전이 각각 2건씩" 오탐)
            assertThat(renderer.verifyMerchantCounts("카카오페이, 토스페이 결제가 각각 3건씩 있었어요.", ctx34()))
                    .isTrue();
            assertThat(renderer.verifyMerchantCounts("카카오페이, 토스페이 결제가 각각 2건씩 있었어요.", ctx34()))
                    .isFalse();
        }

        @Test
        @DisplayName("묶인 거래 횟수도 검사: '롯데월드 3회' 거절, '롯데월드를 네 번' 통과")
        void groupedTransactionCount() {
            assertThat(renderer.verifyMerchantCounts("롯데월드 3회 방문으로 오락문화가 늘었어요.", ctx34()))
                    .isFalse();
            assertThat(renderer.verifyMerchantCounts("롯데월드를 네 번 다녀오면서 오락문화가 늘었어요.", ctx34()))
                    .isTrue();
        }

        @Test
        @DisplayName("검사하지 않는 경우: '등'·'포함'이 붙은 주장, 거래처 이름 없는 건수, 건수 없는 언급")
        void notChecked() {
            assertThat(renderer.verifyMerchantCounts("토스페이 출금 등 13건이 모두 필요한 지출이었어요.", ctx34()))
                    .isTrue();
            assertThat(renderer.verifyMerchantCounts("기타 7건이 쌓였어요.", ctx34())).isTrue();
            // exp9b 30주차 오탐: 예시("같은")를 든 뒤 카테고리 전체 건수
            assertThat(renderer.verifyMerchantCounts("카카오페이 같은 결제가 7건 이어졌어요.", ctx34()))
                    .isTrue();
            assertThat(renderer.verifyMerchantCounts("토스페이·카카오페이 결제가 여러 건 있었어요.", ctx34()))
                    .isTrue();
        }

        @Test
        @DisplayName("'한 건'은 여러 거래 중 하나를 가리키므로 총 건수 주장으로 보지 않음 (exp9 28·38주차 오탐)")
        void singleTransactionReference_notTreatedAsTotal() {
            assertThat(renderer.verifyMerchantCounts("카카오페이 한 건이 평소 기타 한 건 금액의 5.2배였어요.", ctx34()))
                    .isTrue();
            assertThat(renderer.verifyMerchantCounts("토스페이로 결제한 한 건이 158,355원이었어요.", ctx34()))
                    .isTrue();
        }

        @Test
        @DisplayName("거래처 계열·모르는 이름 (exp10): '토스·네이버페이 6건'(토스페이 4 + 토스 1 + 네이버페이 1) 통과, '케이스포츠·노래방 7건' 검사 제외")
        void familyNameAndUnknownName() {
            java.util.Map<String, Integer> etc = new java.util.LinkedHashMap<>();
            etc.put("토스페이", 4);
            etc.put("네이버페이결제", 1);
            etc.put("토스", 1);
            java.util.Map<String, Integer> fun = new java.util.LinkedHashMap<>();
            fun.put("케이스포츠", 5);
            fun.put("렉스코인노래연습장", 2);
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(
                            new CategoryOverspend("기타", 300_000, 140_203, null, 6, etc),
                            new CategoryOverspend("오락문화", 14_143, 18_000, null, 7, fun)))
                    .build();

            assertThat(renderer.verifyMerchantCounts("토스·네이버페이 거래가 6건 있었어요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyMerchantCounts("토스페이·네이버페이 거래가 6건 있었어요.", ctx))
                    .isFalse(); // 4 + 1 = 5
            assertThat(renderer.verifyMerchantCounts("오락문화는 케이스포츠·노래방 7건으로 쌓였어요.", ctx))
                    .isTrue();
        }

        @Test
        @DisplayName("재시도 연동: 1차 '카카오페이·토스페이 결제가 7건' 거절 → 2차 '합쳐 6건' 채택")
        void retryOnMerchantCountMismatch() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString()))
                    .thenReturn("기타는 카카오페이·토스페이 결제가 7건 발생했어요.")
                    .thenReturn("기타는 카카오페이·토스페이 거래가 합쳐 6건이었어요.");

            Rendered result = renderer.render(ctx34());

            assertThat(result.text()).isEqualTo("기타는 카카오페이·토스페이 거래가 합쳐 6건이었어요.");
            verify(client, times(2)).complete(anyString(), anyString());
        }
    }

    // ── 금액 형식 인식 (#111, exp4: "5만 8,970원" 오인식 4건, "26만 9천 원" 미검사) ──

    @Nested
    @DisplayName("금액 형식 — 복합 표기 추출")
    class MoneyFormatTest {

        @Test
        @DisplayName("N만 N,NNN원 / N만 N천 원 / N천 N백 원 — exp1~4 실제 문장")
        void compoundKoreanAmounts() {
            assertThat(renderer.extractMoneyValues("회식 5만 8,970원이 평소 한 건 금액 1만 900원의 5.4배"))
                    .containsExactly(58_970L, 10_900L);
            assertThat(renderer.extractMoneyValues("이번 주 지출이 26만 9천원으로, 전주보다 10만 5천원 줄었어요"))
                    .containsExactly(269_000L, 105_000L);
            assertThat(renderer.extractMoneyValues("저축 목표에서 5만 4,586원 멀어졌어요. 37만 4,740원을 썼어요"))
                    .containsExactly(54_586L, 374_740L);
            assertThat(renderer.extractMoneyValues("식료품에서 7천 8백 원, 교통에서 8천 원 정도"))
                    .containsExactly(7_800L, 8_000L);
            assertThat(renderer.extractMoneyValues("1만 5천 3백 원")).containsExactly(15_300L);
        }

        @Test
        @DisplayName("기존 형식 유지: N만원, N.N만원, N,NNN원, 퍼센트 무시")
        void existingFormatsKept() {
            assertThat(renderer.extractMoneyValues("12만원과 5,000원, 8.4만원을 썼어요. 35%는 무시."))
                    .containsExactly(120_000L, 5_000L, 84_000L);
            assertThat(renderer.extractMoneyValues("5만 원 그리고 3,000원")).containsExactly(50_000L, 3_000L);
        }

        @Test
        @DisplayName("만원 뒤 다른 숫자가 금액이 아니면 합치지 않음: '5만 원 3건'")
        void notMergedAcrossNonMoney() {
            assertThat(renderer.extractMoneyValues("5만 원 3건이 나갔어요")).containsExactly(50_000L);
        }

        @Test
        @DisplayName("'5~6만 원대'는 5만~6.9만 범위 (exp3 31주차 오탐: 53,900·59,100원)")
        void rangeWithStart() {
            assertThat(renderer.extractMoneyMentions("FBS 출금이 5~6만 원대로 여러 건"))
                    .containsExactly(new FeedbackRenderer.MoneyMention(60_000, true, 50_000));

            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .weekHighlights(List.of(new FeedbackRenderer.TransactionHighlight(
                            java.time.LocalDate.of(2026, 9, 6), "기타", 53_900, "FBS 출금", "NECESSARY")))
                    .build();
            assertThat(renderer.verifyNumbers("FBS 출금이 5~6만 원대로 여러 건 있었어요.", ctx))
                    .isTrue();
        }

        @Test
        @DisplayName("복합 표기도 숫자 검증: '5만 8,970원' 통과, '6만 8,970원' 거절")
        void compoundVerified() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .weekHighlights(List.of(new FeedbackRenderer.TransactionHighlight(
                            java.time.LocalDate.of(2026, 6, 14), "기타", 58_970, "회식", "IMPULSE")))
                    .build();

            assertThat(renderer.verifyNumbers("회식 5만 8,970원이 나갔어요.", ctx)).isTrue();
            assertThat(renderer.verifyNumbers("회식 6만 8,970원이 나갔어요.", ctx)).isFalse();
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
        @DisplayName("구간 폭은 N의 끝자리 0 개수만큼: '70만 원대' = 70만~79만, '150만 원대' = 150만~159만 (exp3 38주차)")
        void rangeWidth_followsTrailingZeros() {
            assertThat(FeedbackRenderer.manWonRangeWidth(60_000)).isEqualTo(10_000);
            assertThat(FeedbackRenderer.manWonRangeWidth(160_000)).isEqualTo(10_000);
            assertThat(FeedbackRenderer.manWonRangeWidth(700_000)).isEqualTo(100_000);
            assertThat(FeedbackRenderer.manWonRangeWidth(1_000_000)).isEqualTo(1_000_000);
            assertThat(FeedbackRenderer.manWonRangeWidth(1_500_000)).isEqualTo(100_000);

            assertThat(renderer.verifyNumbers("격차를 70만 원대로 낮출 수 있어요.", ctxWith(725_938)))
                    .isTrue();
            assertThat(renderer.verifyNumbers("격차를 70만 원대로 낮출 수 있어요.", ctxWith(805_000)))
                    .isFalse();
            assertThat(renderer.verifyNumbers("150만 원대 지출이에요.", ctxWith(1_555_000)))
                    .isTrue();
            assertThat(renderer.verifyNumbers("150만 원대 지출이에요.", ctxWith(1_600_000)))
                    .isFalse();
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

        @Test
        @DisplayName("프롬프트에 절약 액션 목록 대신 코드가 붙인다는 안내와 가장 큰 한 건이 들어간다 (#146)")
        void prompt_hidesActionsAndShowsLargest() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .savingsImpactWon(-300_000)
                    .actions(List.of(new ActionSummary("기타", 100_000, null, 1.0, 180_000)))
                    .weekHighlights(List.of(
                            new FeedbackRenderer.TransactionHighlight(
                                    java.time.LocalDate.of(2026, 5, 3), "식대", 130_000, null, null),
                            new FeedbackRenderer.TransactionHighlight(
                                    java.time.LocalDate.of(2026, 5, 4), "기타", 50_000, null, null)))
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("[절약 액션] 마지막 문장은 시스템이 붙인다")
                    .contains("[이번 주 가장 큰 한 건] 식대 130,000원")
                    .doesNotContain("까지만 쓰기");
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
                            "기타",
                            6.83,
                            117_830,
                            new FeedbackRenderer.AnomalyBasis(true, 94_000, 13_759, "오픈뱅킹출금 토스페이", 8))
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("- 기타: 한 건 \"오픈뱅킹출금 토스페이\" 94,000원이 평소 기타 한 건 금액(최근 8주 한 건 중앙값 13,759원)의 6.8배 [한 건 배수]"
                            + " / 기타 이번 주 합계: 117,830원")
                    .doesNotContain("평소 대비 약");
        }

        @Test
        @DisplayName("추세 이상치: 최근 2주 주당 평균 vs 그 앞 4주 주당 평균으로 표기")
        void trend_basisSpelledOut() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .anomaly("식대", 1.8, 210_000, new FeedbackRenderer.AnomalyBasis(false, 180_000, 100_000, null))
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("- 식대: 최근 2주 주당 평균 180,000원이 그 앞 4주 주당 평균(100,000원)의 1.8배 [주간 금액 배수]");
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
                    .overspendCategories(List.of(new CategoryOverspend("식대", 191_731, 139_940, cs, 9)))
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("(이번 주 금액이 최근 4주 주당 평균의 1.7배) — 원인: 횟수 증가")
                    .doesNotContain("과거 대비");
        }

        @Test
        @DisplayName("횟수: 초과 카테고리에 이번 주 건수, 잘린 메모 목록에 'N건 중 M건만 표시' (exp3 35주차 롯데월드 4건 → '3회')")
        void count_spelledOutAndTruncationLabeled() {
            List<com.planus.backend.domain.aifeedback.cause.MemoEvidence> memos = List.of(
                    new com.planus.backend.domain.aifeedback.cause.MemoEvidence(
                            java.time.LocalDate.of(2026, 10, 2), 29_150, "체크카드 롯데월드"),
                    new com.planus.backend.domain.aifeedback.cause.MemoEvidence(
                            java.time.LocalDate.of(2026, 10, 2), 29_150, "체크카드 롯데월드"),
                    new com.planus.backend.domain.aifeedback.cause.MemoEvidence(
                            java.time.LocalDate.of(2026, 10, 2), 29_150, "체크카드 롯데월드"),
                    new com.planus.backend.domain.aifeedback.cause.MemoEvidence(
                            java.time.LocalDate.of(2026, 10, 2), 29_150, "체크카드 롯데월드"));
            CauseSignals cs =
                    new CauseSignals(11.1, "BOTH", 0.5, 0.5, java.util.Map.of(), java.util.Map.of(), null, memos, true);
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(new CategoryOverspend("오락문화", 25_691, 116_600, cs, 4)))
                    .anomaly("오락문화", 14.6, 116_600, new FeedbackRenderer.AnomalyBasis(true, 29_150, 2_000, "체크카드 롯데월드"))
                    .anomalyWeeklyCount(4)
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("(이번 주 지출 116,600원, 4건)")
                    .contains("메모 (이번 주 4건 중 3건만 표시):")
                    .contains("/ 오락문화 이번 주 합계: 116,600원 (4건)");
        }

        @Test
        @DisplayName("묶인 메모는 '× N건'으로 표기하고, 묶음이 이번 주 건수를 모두 덮으면 잘림 표기 없음")
        void groupedMemo_countSuffix_noTruncationLabel() {
            CauseSignals cs = new CauseSignals(
                    11.1,
                    "BOTH",
                    0.5,
                    0.5,
                    java.util.Map.of(),
                    java.util.Map.of(),
                    null,
                    List.of(new com.planus.backend.domain.aifeedback.cause.MemoEvidence(
                            java.time.LocalDate.of(2026, 10, 2), 29_150, "체크카드 롯데월드", 4)),
                    true);
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(new CategoryOverspend("오락문화", 25_691, 116_600, cs, 4)))
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("[2026-10-02] \"체크카드 롯데월드\" (29,150원 × 4건)")
                    .contains("  메모:\n")
                    .doesNotContain("건만 표시");
        }

        @Test
        @DisplayName("주목 거래: 자르기 전에 묶음 — 롯데월드 4건이 상위 5건 제한에 잘려 '× 3건'이 되지 않음")
        void highlights_groupedBeforeLimit() {
            java.time.LocalDate d = java.time.LocalDate.of(2026, 10, 2);
            List<FeedbackRenderer.TransactionHighlight> raw = new java.util.ArrayList<>();
            raw.add(new FeedbackRenderer.TransactionHighlight(d, "기타", 185_150, "토스 후불결제", "NECESSARY"));
            raw.add(new FeedbackRenderer.TransactionHighlight(d, "식대", 39_500, "비에이치씨", "SATISFIED"));
            for (int i = 0; i < 4; i++) {
                raw.add(new FeedbackRenderer.TransactionHighlight(d, "오락문화", 29_150, "체크카드 롯데월드", "SATISFIED"));
            }
            raw.add(new FeedbackRenderer.TransactionHighlight(d, "기타", 25_000, "토스페이", "NECESSARY"));
            raw.add(new FeedbackRenderer.TransactionHighlight(d, "기타", 10_000, "카카오페이", "NECESSARY"));

            List<FeedbackRenderer.TransactionHighlight> top =
                    FeedbackRenderer.TransactionHighlight.groupIdenticalTopN(raw, 5);

            assertThat(top).hasSize(5);
            assertThat(top).anySatisfy(h -> {
                assertThat(h.memo()).isEqualTo("체크카드 롯데월드");
                assertThat(h.count()).isEqualTo(4);
            });
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .weekHighlights(top)
                    .build();
            assertThat(capturedUserPrompt(ctx)).contains("오락문화 29,150원 × 4건 \"체크카드 롯데월드\"");
        }

        @Test
        @DisplayName("묶음 합계(금액 × 건수)를 LLM이 써도 숫자 검증 통과")
        void groupedTotal_allowedInVerification() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .weekHighlights(List.of(new FeedbackRenderer.TransactionHighlight(
                            java.time.LocalDate.of(2026, 10, 3), "기타", 10_000, "카카오페이", "NECESSARY", 2)))
                    .build();

            assertThat(renderer.verifyNumbers("카카오페이 10,000원 두 건, 합계 20,000원이 나갔어요.", ctx))
                    .isTrue();
        }

        @Test
        @DisplayName("절약 액션에 없는 초과 카테고리에 '[절약 액션 대상 아님]' 표시 (exp5 33주차: 만족 소비라 제외된 식대 절약액을 지어냄)")
        void overspendWithoutAction_marked() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(
                            new CategoryOverspend("식대", 191_731, 139_940, null, 9),
                            new CategoryOverspend("기타", 204_821, 58_250, null, 4)))
                    .actions(List.of(new ActionSummary("기타", 73_932, null, 0.5)))
                    .build();

            String prompt = capturedUserPrompt(ctx);
            assertThat(prompt).contains("- 식대: 월말 예상 초과 +191,731원 (이번 주 지출 139,940원, 9건) [절약 액션 대상 아님]");
            assertThat(prompt).contains("- 기타: 월말 예상 초과 +204,821원 (이번 주 지출 58,250원, 4건)\n");
        }

        @Test
        @DisplayName("초과 카테고리 금액은 '월말 예상 초과'로 표기 — 누적으로 표기하면 월초에 '이미 51만 원 누적'이라고 씀 (#122 실험 0, 39주차)")
        void overspend_labeledAsProjection() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(new CategoryOverspend("기타", 512_900, 117_830, null, 5)))
                    .build();

            String prompt = capturedUserPrompt(ctx);
            assertThat(prompt).contains("- 기타: 월말 예상 초과 +512,900원 (이번 주 지출 117,830원, 5건)");
            assertThat(prompt).doesNotContain("누적");
        }

        @Test
        @DisplayName("감정 태그는 한국어로 표기 — 영문 코드가 있으면 'SATISFIED 태그'처럼 그대로 노출 (#122 실험 0, 7/68)")
        void emotionTags_renderedInKorean() {
            CauseSignals cs = new CauseSignals(
                    null,
                    "FREQUENCY",
                    1.0,
                    0.0,
                    java.util.Map.of("SATISFIED", 3L, "STRESS_RELIEF", 1L),
                    java.util.Map.of("SATISFIED", 30_000L, "STRESS_RELIEF", 5_000L),
                    null,
                    List.of(),
                    false);
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .weekEmotion(new FeedbackRenderer.WeekEmotionSummary(
                            java.util.Map.of("NECESSARY", 2L, "REGRET", 1L),
                            java.util.Map.of("NECESSARY", 20_000L, "REGRET", 2_000L),
                            0L))
                    .overspendCategories(List.of(new CategoryOverspend("식대", 50_000, 35_000, cs, 4)))
                    .anomaly("기타", 6.5, 117_830, new FeedbackRenderer.AnomalyBasis(true, 94_000, 14_355, "토스페이"))
                    .anomalyEmotion("NECESSARY")
                    .weekHighlights(List.of(new FeedbackRenderer.TransactionHighlight(
                            java.time.LocalDate.of(2026, 10, 2), "기타", 94_000, "토스페이", "IMPULSE")))
                    .build();

            String prompt = capturedUserPrompt(ctx);
            assertThat(prompt)
                    .contains("- 필요 2건 / 20,000원")
                    .contains("- 후회 1건 / 2,000원")
                    .contains("만족 3건/30,000원")
                    .contains("스트레스 해소 1건/5,000원")
                    .contains(", 감정: 필요")
                    .contains("\"토스페이\" (충동)");
            assertThat(prompt).doesNotContainPattern("NECESSARY|SATISFIED|IMPULSE|STRESS_RELIEF|REGRET");
        }

        @Test
        @DisplayName("체념 표현은 거절 — 사용자 판정 (#122 실험 3, 30주차 '이번 달은 있는 그대로 받아들이고')")
        void resignedTone_rejected() {
            assertThat(renderer.verifyTone("이번 달은 있는 그대로 받아들이고 9월부터 기타 예산을 조정해보세요."))
                    .isFalse();
            assertThat(renderer.verifyTone("이번 달 안에 다 메우긴 어렵지만 다음 달엔 줄여봐요.")).isFalse();
            assertThat(renderer.verifyTone("남은 기간이 16일이라 다 메우긴 어려워 보여요.")).isFalse(); // 실험 4 19주차
            assertThat(renderer.verifyTone("이번 달 안에 메우기는 어려울 것 같아요.")).isFalse();
            assertThat(renderer.verifyTone("기타에서 9,718원 줄이면 월말 초과가 22만 원대로 줄어요. 다음 달엔 기타 예산을 조정해보세요."))
                    .isTrue();
        }

        @Test
        @DisplayName("체념 표현이 있으면 재시도하고, 다음 응답을 사용")
        void resignedTone_retried() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString()))
                    .thenReturn("이번 달은 받아들이고 다음 달을 준비해봐요.")
                    .thenReturn("다음 달엔 기타 지출을 미리 정해두면 좋겠어요.");

            FeedbackRenderer.Rendered out = renderer.render(contextBuilder().build());

            assertThat(out.text()).isEqualTo("다음 달엔 기타 지출을 미리 정해두면 좋겠어요.");
            verify(client, org.mockito.Mockito.times(2)).complete(anyString(), anyString());
        }

        @Test
        @DisplayName("내부 용어 '절약 액션'·'페이싱'·'태그'·영문 감정 이름 거절 — 사용자 판정 (#122 실험 6, #140 규칙 I)")
        void bannedWording_rejected() {
            assertThat(renderer.verifyWording("기타와 식료품에서 절약 액션을 모두 지켜도 초과가 남아요."))
                    .isFalse();
            assertThat(renderer.verifyWording("모두 만족 태그라 괜찮아요.")).isFalse();
            assertThat(renderer.verifyWording("필요 지출로 태그하셨으니 불가피했어요.")).isFalse();
            assertThat(renderer.verifyWording("모두 NECESSARY로 기록됐어요.")).isFalse();
            assertThat(renderer.verifyWording("보통 때 페이싱(102%)보다 아껴 썼어요.")).isFalse();
            assertThat(renderer.verifyWording("모두 만족한 지출이라 괜찮아요.")).isTrue();
            assertThat(renderer.verifyWording("기타에서 27만 원 줄이면 월말 초과가 36만 원으로 줄어요."))
                    .isTrue();
        }

        @Test
        @DisplayName("'각각' 금액은 카테고리마다 맞아야 통과 (#122 실험 6, 18주차 '식료품과 오락문화에서 각각 2~3만 원씩')")
        void eachAmount_checkedPerCategory() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(
                            new CategoryOverspend("식료품", 29_675, 17_900, null, 3),
                            new CategoryOverspend("오락문화", 14_143, 18_000, null, 7)))
                    .build();

            assertThat(renderer.verifyEach("식료품과 오락문화에서 각각 2~3만 원씩 초과될 수 있어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyEach("식료품과 오락문화가 각각 1~3만 원 정도 넘을 것 같아요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyEach("식료품과 오락문화에서 각각 29,675원, 14,143원 넘을 것 같아요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyEach("식료품은 3만 원, 오락문화는 1만 원 정도 넘을 것 같아요.", ctx))
                    .isTrue();
        }

        @Test
        @DisplayName("비율이 줄었다고 쓰면 거절 (#122 실험 8, 21주차)")
        void rateDown_rejected() {
            assertThat(renderer.verifyWording("전주보다 14만 원 줄이면서 이번 달 예산의 104%까지 내려왔어요."))
                    .isFalse();
            assertThat(renderer.verifyWording("이맘때 142% 쓰던 패턴이 지금은 113%로 줄었어요."))
                    .isFalse();
        }

        @Test
        @DisplayName("'이번 주' 문장의 예산 비율은 '지금까지·누적·월말'이 붙거나 이번 주 지출 비율일 때만 통과 (#144, #140 규칙 A)")
        void weekRate_onlyWeekShareOrQualified() {
            // 이번 주 395,231원 / 가용예산 1,000,000원 = 40%, 누적 15%, 이대로면 월말 141%
            FeedbackContext ctx = contextBuilder()
                    .weekTotalWon(395_231)
                    .availableBudgetWon(1_000_000)
                    .predictedMonthEndWon(1_410_000)
                    .pacing(new com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult(
                            0.15,
                            0.19,
                            0.79,
                            null,
                            3,
                            com.planus.backend.domain.aifeedback.core.PacingComparator.PacingDisplay.MEDIAN,
                            null,
                            null))
                    .build();

            // 누적 비율을 이번 주에 붙임 — 205609a에서 허용했던 표현 (그 주의 15%는 누적, #140)
            assertThat(renderer.verifyRate("이번 주는 이번 달 예산의 15%를 써서 평소보다 느린 속도예요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyRate("이번 주 39만 원을 쓰면서 이번 달 예산의 15%를 썼어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyRate("이번 주 28만 9천 원을 쓰면서 예산의 109%를 사용했어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyRate("이번 주는 289,142원을 써서 예산을 109% 사용했어요.", ctx))
                    .isFalse();
            // 이번 주 지출 비율이면 통과
            assertThat(renderer.verifyRate("이번 주는 이번 달 예산의 40%를 썼어요.", ctx)).isTrue();
            assertThat(renderer.verifyRate("이번 주는 예산의 41%를 썼어요.", ctx)).isTrue(); // 반올림 ±1
            // 누적·월말임을 밝히면 통과
            assertThat(renderer.verifyRate("이번 주는 39만 원을 써서 지금까지 이번 달 예산의 15%를 썼어요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyRate("이번 주 총 291,540원을 쓰셨는데, 이대로면 월말엔 예산의 141%를 써서 멀어질 것 같아요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyRate("이번 주는 전주보다 7만 원 덜 썼어요. 지금까지 예산의 70%를 썼어요.", ctx))
                    .isTrue(); // 다른 문장 ('이번 주' 없음)
            // 누적·월말을 밝혀도 숫자가 틀리면 거절 (리뷰 지적: 수식어만 보고 건너뛰던 문제)
            assertThat(renderer.verifyRate("이번 주는 39만 원을 써서 지금까지 이번 달 예산의 57%를 썼어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyRate("이번 주에도 많이 써서 이대로면 월말엔 예산의 160%예요.", ctx))
                    .isFalse();
            // 소수·공백 표기도 같은 기준
            assertThat(renderer.verifyRate("이번 주는 이번 달 예산의 15.0%를 썼어요.", ctx)).isFalse();
            assertThat(renderer.verifyRate("이번 주는 이번 달 예산의 40.0 %를 썼어요.", ctx)).isTrue();
            // 숫자로 바꿀 수 없는 비율은 예외 대신 검증 실패
            assertThat(renderer.verifyRate("이번 주는 예산의 9223372036854775808999%를 썼어요.", ctx))
                    .isFalse();
        }

        @Test
        @DisplayName("초과 예상인데 '예산 안에서 잘', 초과 문장의 'N원 넘게'가 실제보다 크면 거절 (#122 실험 8, 26·24주차)")
        void contradictingFacts_rejected() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .savingsImpactWon(-278_982)
                    .overspendCategories(List.of(new CategoryOverspend("식대", 119_176, 124_200, null, 7)))
                    .build();

            assertThat(renderer.verifyFacts("이번 주는 예산 안에서 잘 쓰고 계세요 👍", ctx)).isFalse();
            assertThat(renderer.verifyFacts("식대도 이번 주 7건으로 12만 원 넘게 초과될 것 같아요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyFacts("식대도 11만 원 넘게 초과될 것 같아요.", ctx)).isTrue();
            assertThat(renderer.verifyFacts("월말 초과가 27만 원 넘게 예상돼요.", ctx)).isTrue();
            assertThat(renderer.verifyFacts("식대에서 이번 주 12만 원 넘게 썼어요.", ctx)).isTrue(); // 초과 문장 아님
        }

        @Test
        @DisplayName("월말 예상 사용 비율과 '전체 월말 예상 초과' 이름 표시 (#122 실험 8, 31·27주차)")
        void projectedRateAndTotalLabel() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .predictedMonthEndWon(1_358_038)
                    .savingsImpactWon(-358_038)
                    .pacing(new com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult(
                            0.30,
                            0.20,
                            1.5,
                            null,
                            3,
                            com.planus.backend.domain.aifeedback.core.PacingComparator.PacingDisplay.MEDIAN,
                            null,
                            null))
                    .build();

            String prompt = capturedUserPrompt(ctx);
            assertThat(prompt).contains("- 이대로면 월말엔 예산의 136%\n");
            assertThat(prompt).contains("358,038원 멀어짐 (= 전체 월말 예상 초과.");
        }

        @Test
        @DisplayName("재시도 때 거절 이유를 프롬프트에 붙인다 (#122 실험 9: 같은 오류 3번 반복 → 폴백)")
        void retry_includesRejectionReason() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString()))
                    .thenReturn("모두 만족 태그가 붙은 지출이에요.")
                    .thenReturn("다음 달엔 기타 지출을 미리 정해두면 좋겠어요.");

            FeedbackRenderer.Rendered out = renderer.render(contextBuilder().build());

            assertThat(out.text()).isEqualTo("다음 달엔 기타 지출을 미리 정해두면 좋겠어요.");
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, times(2)).complete(anyString(), captor.capture());
            assertThat(captor.getAllValues().get(0)).doesNotContain("직전 답변이 거절된 이유");
            assertThat(captor.getAllValues().get(1)).contains("[직전 답변이 거절된 이유: 내부 용어·기간 표기]");
        }

        @Test
        @DisplayName("예산 사용 비율 줄: '지금까지 이번 달 예산의 N%' + 평소 대비 속도")
        void pacingLine_currentFirstWithSpeed() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .pacing(new com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult(
                            1.04,
                            1.37,
                            0.76,
                            null,
                            3,
                            com.planus.backend.domain.aifeedback.core.PacingComparator.PacingDisplay.MEDIAN,
                            null,
                            null))
                    .build();

            assertThat(capturedUserPrompt(ctx)).contains("- 지금까지 이번 달 예산의 104%를 씀 (보통 이맘때는 137% — 평소보다 느린 속도)\n");
        }

        @Test
        @DisplayName("'N원 넘게' 하한 검사는 초과 금액에만 — 같은 문장의 이번 주 지출액은 제외 (PR 리뷰)")
        void atLeast_onlyForOverspendAmounts() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .savingsImpactWon(-100_000)
                    .overspendCategories(List.of(new CategoryOverspend("기타", 80_000, 310_000, null, 5)))
                    .build();

            assertThat(renderer.verifyFacts("이번 주 30만 원 넘게 썼고, 월말 초과는 10만 원으로 예상돼요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyFacts("이번 주 30만 원 넘게 쓰면서 월말 초과는 10만 원으로 예상돼요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyFacts("이대로면 월말에 30만 원 넘게 초과할 것 같아요.", ctx))
                    .isFalse();
        }

        @Test
        @DisplayName("남는 초과액을 절약 금액으로 권하면 거절 — 절약 금액은 그 카테고리의 액션 금액과 대조 (PR 리뷰)")
        void savingsAmount_mustMatchActionReduction() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .savingsImpactWon(-200_000)
                    .actions(List.of(new ActionSummary("기타", 50_000, null, 0.5)))
                    .build();

            assertThat(renderer.verifySavingsAmounts("기타에서 150,000원을 줄여보세요.", ctx))
                    .isFalse();
            assertThat(renderer.verifySavingsAmounts("기타에서 5만 원 줄이면 월말 초과가 20만 원에서 15만 원으로 줄어요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyNumbers("기타에서 5만 원 줄이면 월말 초과가 20만 원에서 15만 원으로 줄어요.", ctx))
                    .isTrue();
            assertThat(renderer.verifySavingsAmounts("남은 기간 50,000원만 아끼면 초과가 150,000원으로 낮아져요.", ctx))
                    .isTrue();
        }

        @Test
        @DisplayName("여러 카테고리를 함께 말하면 그 카테고리들의 액션 합과 대조")
        void savingsAmount_multipleCategoriesUseSum() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .savingsImpactWon(-500_013)
                    .actions(List.of(
                            new ActionSummary("교통", 2_362, null, 0.5),
                            new ActionSummary("의류신발", 5_386, null, 0.5),
                            new ActionSummary("기타", 18_118, null, 0.5)))
                    .build();

            assertThat(renderer.verifySavingsAmounts("교통·의류신발·기타에서 총 25,866원 줄이면 초과가 줄어요.", ctx))
                    .isTrue();
            assertThat(renderer.verifySavingsAmounts("기타에서 25,866원 줄이면 초과가 줄어요.", ctx))
                    .isFalse();
        }

        @Test
        @DisplayName("모르는 감정 코드는 그대로 둔다")
        void emotionLabel_unknownKept() {
            assertThat(FeedbackRenderer.emotionLabel("CALM")).isEqualTo("CALM");
            assertThat(FeedbackRenderer.emotionLabel(null)).isNull();
        }

        @Test
        @DisplayName("절약 액션이 없는 주는 '[절약 액션] 없음' 명시 (exp5 17주차: '월 5~10만 원 아낄 수' 지어냄)")
        void noActions_explicitlyStated() {
            FeedbackContext ctx =
                    contextBuilder().feedbackType(FeedbackType.ALERT).build();

            assertThat(capturedUserPrompt(ctx)).contains("[절약 액션] 없음 — 절약 금액이나 절약 효과(원)를 제시하지 말 것");
            assertThat(capturedSystemPrompt(ctx)).contains("줄이기를 권하더라도 금액을 붙이지 않는다");
        }

        @Test
        @DisplayName("월말 결산 규칙: 개선 제안에 금액·절약 효과를 붙이지 않음 (exp2·3 3월 결산 '월 15만 원 아낄 수')")
        void monthEnd_noSavingsAmountRule() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString())).thenReturn("이번 달 결산이에요.");
            renderer.renderMonthEnd(new FeedbackRenderer.MonthEndContext(
                    java.time.YearMonth.of(2026, 3), 1_940_547, 1_000_000, -940_547, List.of(), List.of()));

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client).complete(captor.capture(), anyString());
            assertThat(captor.getValue()).contains("제안에는 금액(원)이나 절약 효과를 붙이지 않는다");
        }

        @Test
        @DisplayName("거래처별 건수 줄 — 카테고리 건수를 결제 수단에 붙이지 않도록 (exp7 34주차 '토스페이·카카오페이 7건')")
        void merchantCounts_line() {
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            counts.put("카카오페이", 3);
            counts.put("토스페이", 3);
            counts.put("네이버페이결제", 1);
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(new CategoryOverspend("기타", 346_563, 141_742, null, 7, counts)))
                    .build();

            assertThat(capturedUserPrompt(ctx)).contains("  거래처별: 카카오페이 3건, 토스페이 3건, 네이버페이결제 1건\n");
            assertThat(capturedSystemPrompt(ctx)).contains("'거래처별' 표기의 숫자를 그대로 쓴다");
        }

        @Test
        @DisplayName("거래처가 5곳을 넘으면 상위 5곳 + '외 N건', 이번 주 1건이면 거래처 줄 생략")
        void merchantCounts_limitAndSingle() {
            java.util.Map<String, Integer> many = new java.util.LinkedHashMap<>();
            many.put("토스페이", 5);
            many.put("KOCES_하남하머니", 2);
            many.put("네이버페이결제", 2);
            many.put("쿠팡", 1);
            many.put("스마트로_대표비인증", 1);
            many.put("네이버페이충전", 1);
            many.put("키오스크_KICC_1", 1);
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(
                            new CategoryOverspend("기타", 1_006_344, 550_850, null, 13, many),
                            new CategoryOverspend("교통", 27_100, 14_000, null, 1, java.util.Map.of("카카오T일반택시", 1))))
                    .build();

            String prompt = capturedUserPrompt(ctx);
            assertThat(prompt)
                    .contains("  거래처별: 토스페이 5건, KOCES_하남하머니 2건, 네이버페이결제 2건, 쿠팡 1건, 스마트로_대표비인증 1건 외 2건\n")
                    .doesNotContain("거래처별: 카카오T일반택시");
        }

        @Test
        @DisplayName("목록이 잘리지 않으면 기존 '메모:' 표기")
        void count_notTruncated_plainMemoHeader() {
            List<com.planus.backend.domain.aifeedback.cause.MemoEvidence> memos =
                    List.of(new com.planus.backend.domain.aifeedback.cause.MemoEvidence(
                            java.time.LocalDate.of(2026, 7, 5), 14_000, "체크카드 카카오T일반택시"));
            CauseSignals cs =
                    new CauseSignals(null, null, 0, 0, java.util.Map.of(), java.util.Map.of(), null, memos, true);
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .overspendCategories(List.of(new CategoryOverspend("교통", 27_100, 14_000, cs, 1)))
                    .build();

            assertThat(capturedUserPrompt(ctx)).contains("  메모:\n").doesNotContain("건만 표시");
        }

        @Test
        @DisplayName("주목 거래는 일부 목록임을 명시 + 시스템 규칙에 '목록 줄 수를 횟수로 쓰지 않음'")
        void highlightsLabeledAndCountRule() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .weekHighlights(List.of(new FeedbackRenderer.TransactionHighlight(
                            java.time.LocalDate.of(2026, 10, 2), "오락문화", 29_150, "체크카드 롯데월드", "SATISFIED")))
                    .build();

            assertThat(capturedUserPrompt(ctx)).contains("[이번 주 주목 거래 — 금액 상위 일부, 전체 목록 아님]");
            assertThat(capturedSystemPrompt(ctx)).contains("목록 줄 수를 세어 횟수로 쓰지 않는다");
        }

        @Test
        @DisplayName("시스템 프롬프트: 말투·구조 블록이 숫자 규칙보다 앞에 있고, 숫자 개수 제한과 높임 어미 금지를 담는다 (#132)")
        void systemPrompt_toneComesFirst() {
            FeedbackContext ctx =
                    contextBuilder().feedbackType(FeedbackType.ALERT).build();

            String system = capturedSystemPrompt(ctx);

            assertThat(system.indexOf("[말투와 구조")).isGreaterThanOrEqualTo(0);
            assertThat(system.indexOf("[말투와 구조")).isLessThan(system.indexOf("[숫자·사실 규칙"));
            assertThat(system)
                    .contains("문장은 최대 3개")
                    .contains("반말('~했어', '~하자')은 쓰지 않는다")
                    .contains("이번 주 총지출 외에 꼭 필요한 2개만")
                    .contains("첫 문장은 반드시 이번 주 총지출과 직전 4주 평균 대비로 시작한다")
                    .contains("'쓰셔서', '~하시며' 같은 높임 어미")
                    .contains("나쁜 예:")
                    .contains("좋은 예:");
        }

        @Test
        @DisplayName("월말 시스템 프롬프트: 상투적 격려 금지, 숫자 2개 이내 (#132)")
        void monthEndSystemPrompt_tone() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString())).thenReturn("이번 달 결산이에요.");
            renderer.renderMonthEnd(new FeedbackRenderer.MonthEndContext(
                    java.time.YearMonth.of(2026, 3), 1_940_547, 1_000_000, -940_547, List.of(), List.of()));

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client).complete(captor.capture(), anyString());
            assertThat(captor.getValue())
                    .contains("[말투와 구조")
                    .contains("문장은 최대 4개")
                    .contains("반말('~했어', '~하자')은 쓰지 않는다")
                    .contains("숫자는 2개 이내")
                    .contains("상투적인 격려는 쓰지 않는다")
                    .contains("제안에는 금액(원)이나 절약 효과를 붙이지 않는다");
        }

        @Test
        @DisplayName("시스템 규칙: 배수의 대상 명시 + 반올림은 허용하되 실제 값에서 벗어나지 않게")
        void systemPrompt_multiplierRules() {
            FeedbackContext ctx =
                    contextBuilder().feedbackType(FeedbackType.ALERT).build();

            assertThat(capturedSystemPrompt(ctx))
                    .contains("'한 건' 배수는 그 거래에만 붙이고")
                    .contains("'6배쯤'처럼 반올림해서 쉽게 말해도 되지만")
                    .doesNotContain("소수 첫째 자리까지 그대로 쓴다")
                    .contains("한 건 배수에 쓰지 않는다")
                    .contains("사용자에게 보이는 문장에 쓰지 않는다")
                    .doesNotContain("MTD")
                    .contains("특정 결제 수단이나 거래처의 건수로 바꿔 쓰지 않는다");
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
        @DisplayName("첫 문장만 코드로 보정한 응답은 템플릿 폴백이 아니라 '첫 문장 보정'으로 센다 (#144 리뷰)")
        void firstSentenceCorrection_countedSeparately() {
            String noTotal = "기타에서 큰 결제 한 건이 가장 컸어요.";
            when(llmProvider.getIfAvailable()).thenReturn(meter.wrap(scripted(noTotal, noTotal, noTotal)));
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .availableBudgetWon(1_000_000)
                    .weekTotalWon(185_533)
                    .build();
            meter.begin();
            Rendered result = renderer.render(ctx);
            meter.end(result.text(), "제36주차");

            assertThat(result.text()).isEqualTo("이번 주는 185,533원을 썼어요. " + noTotal);
            assertThat(meter.firstSentenceCorrected()).isEqualTo(1);
            assertThat(meter.fallbackVerify()).isZero();
            assertThat(meter.acceptedLines()).singleElement().asString().contains("| 첫 문장 보정]");
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
    @Nested
    @DisplayName("#144 이번 주 총지출·직전 4주 평균·비율·속도 표기")
    class WeeklyTotalTest {

        /** 36주차와 같은 모양: 이번 주 185,533원, 지난주 483,950원, 직전 4주 평균 320,000원, 이번 달 누적 56%(보통 45%, 빠름). */
        private FeedbackContextBuilder week36() {
            return contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .availableBudgetWon(1_000_000)
                    .weekTotalWon(185_533)
                    .prevWeekTotalWon(483_950)
                    .recent4WeekAvgWon(320_000)
                    .pacing(new com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult(
                            0.56,
                            0.45,
                            1.24,
                            null,
                            3,
                            com.planus.backend.domain.aifeedback.core.PacingComparator.PacingDisplay.MEDIAN,
                            null,
                            null));
        }

        private String userPromptOf(FeedbackContext ctx) {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString()))
                    .thenReturn("이번 주는 185,533원을 써서 최근 4주 평균보다 134,467원 적게 썼어요.");
            renderer.render(ctx);
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, atLeastOnce()).complete(anyString(), captor.capture());
            return captor.getAllValues().getFirst();
        }

        @Test
        @DisplayName("분석 재료에 이번 주 지출 비율, 직전 4주 평균 대비, 이번 주와 이번 달 속도가 반대라는 주의를 넣는다")
        void prompt_hasWeekShareAverageAndDirectionNote() {
            String prompt = userPromptOf(week36().build());

            assertThat(prompt)
                    .contains("- 이번 주 총 변동지출: 185,533원 (지난주 483,950원 대비 -298,417원)")
                    .contains("- 이번 주 지출만은 이번 달 예산의 19%")
                    .contains("- 직전 4주 주당 평균(= 평소): 320,000원 → 이번 주는 평소보다 134,467원 적음")
                    .contains("지금까지 쓴 누적 비율. 이번 주에 쓴 비율이 아님")
                    .contains("- 주의: 이번 주는 평소(직전 4주 평균)보다 적게 썼지만 이번 달 전체로는 보통 이맘때보다 빠른 속도");
        }

        @Test
        @DisplayName("첫 문장에 이번 주 총지출이 없으면 거절 — 반올림 표현은 허용")
        void firstSentence_mustHaveWeekTotal() {
            FeedbackContext ctx = week36().build();

            assertThat(renderer.verifyFirstSentence("이번 주는 18만 5천 원을 써서 최근 4주 평균보다 13만 원 적게 썼어요. 기타가 컸어요.", ctx))
                    .isTrue();
            // 직전 4주 평균이 있으면 평균 대비도 첫 문장에 (리뷰 지적)
            assertThat(renderer.verifyFirstSentence("이번 주는 185,533원을 썼어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyFirstSentence(
                            "이번 주는 185,533원을 썼어요.",
                            week36().recent4WeekAvgWon(0).build()))
                    .isTrue();
            assertThat(renderer.verifyFirstSentence("이번 주는 185,533원으로 평소(32만 원)보다 적게 썼어요.", ctx))
                    .isTrue();
            // 금액이 이번 주 총지출을 가리키는지: '이번 주'가 없으면 거절
            assertThat(renderer.verifyFirstSentence("월말 초과는 185,533원으로 평균보다 13만 원 적어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyFirstSentence("기타에서 큰 결제가 있었어요. 이번 주는 185,533원을 썼어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyFirstSentence("이번 주는 지출이 많이 줄었어요.", ctx)).isFalse();
            // 이번 주 지출이 없으면 검사하지 않는다
            assertThat(renderer.verifyFirstSentence("기록이 없어요.", contextBuilder().build()))
                    .isTrue();
        }

        @Test
        @DisplayName("끝까지 첫 문장 총지출만 빠지면 코드로 만든 첫 문장을 앞에 붙인다")
        void retryExhausted_prependsWeekTotalSentence() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString())).thenReturn("기타에서 큰 결제 한 건이 가장 컸어요.");

            Rendered out = renderer.render(week36().build());

            assertThat(out.text()).isEqualTo("이번 주는 185,533원을 써서 최근 4주 평균보다 134,467원 적게 썼어요. 기타에서 큰 결제 한 건이 가장 컸어요.");
            verify(client, times(3)).complete(anyString(), anyString());
        }

        @Test
        @DisplayName("템플릿 폴백도 이번 주 총지출로 시작한다")
        void template_startsWithWeekTotal() {
            when(llmProvider.getIfAvailable()).thenReturn(null);

            assertThat(renderer.render(week36().build()).text())
                    .startsWith("이번 주는 185,533원을 써서 최근 4주 평균보다 134,467원 적게 썼어요.");
            assertThat(FeedbackRenderer.weekTotalSentence(
                            week36().recent4WeekAvgWon(0).build()))
                    .isEqualTo("이번 주는 185,533원을 썼어요.");
        }

        @Test
        @DisplayName("이번 주가 줄었는데 '이번 주'에 빠른 속도를 붙이면 거절 — 이번 달 범위를 밝히면 통과 (#140 규칙 B②)")
        void pace_mustMatchWeekDirection() {
            FeedbackContext ctx = week36().build();

            assertThat(renderer.verifyPace("이번 주는 평소보다 쓰는 속도가 빠른 편이에요.", ctx)).isFalse();
            assertThat(renderer.verifyPace("이번 주는 평소처럼 지냈어요.", ctx)).isFalse();
            assertThat(renderer.verifyPace("이번 주는 줄었지만 이번 달 전체로는 보통 이맘때보다 빨라요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyPace("이번 주는 평소보다 적게 써서 속도를 늦췄어요.", ctx)).isTrue();
            // 직전 4주 평균이 없으면 방향을 알 수 없어 검사하지 않는다
            assertThat(renderer.verifyPace(
                            "이번 주는 속도가 빨랐어요.", week36().recent4WeekAvgWon(0).build()))
                    .isTrue();
        }

        @Test
        @DisplayName("지난주 대비 금액에 '평소보다'를 붙이면 거절 (#140 규칙 C)")
        void usual_meansRecentFourWeeks() {
            FeedbackContext ctx = week36().build();

            assertThat(renderer.verifyUsual("이번 주는 평소보다 30만 원 덜 썼어요.", ctx)).isFalse(); // 298,417원은 지난주 대비
            assertThat(renderer.verifyUsual("이번 주는 평소보다 13만 원 덜 썼어요.", ctx)).isTrue(); // 134,467원은 4주 평균 대비
            assertThat(renderer.verifyUsual("이번 주는 지난주보다 30만 원 덜 썼어요.", ctx)).isTrue();
        }

        @Test
        @DisplayName("이번 달 속도를 말한 구절은 실제 속도와 같아야 한다 — 좋은 예의 '빠르게'를 느린 주에 옮겨 쓴 사례 (#144 재측정 J082)")
        void monthPace_mustMatchData() {
            // 이번 달 누적 41%, 보통 이맘때 84% → 느림
            FeedbackContext slow = contextBuilder()
                    .pacing(new com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult(
                            0.41,
                            0.84,
                            0.49,
                            null,
                            3,
                            com.planus.backend.domain.aifeedback.core.PacingComparator.PacingDisplay.MEDIAN,
                            null,
                            null))
                    .build();

            assertThat(renderer.verifyMonthPace("이번 주는 많이 썼지만, 이번 달 전체로는 보통 이맘때보다 빠르게 쓰고 있어요.", slow))
                    .isFalse();
            assertThat(renderer.verifyMonthPace("이번 주는 많이 썼지만, 이번 달 전체로는 보통 이맘때보다 느리게 쓰고 있어요.", slow))
                    .isTrue();
            assertThat(renderer.verifyMonthPace("이번 주는 빠르게 늘었어요.", slow)).isTrue(); // 이번 달 구절이 아님
            assertThat(renderer.verifyMonthPace("이번 달 전체로는 빠르게 쓰고 있어요.", week36().build()))
                    .isTrue();
        }

        @Test
        @DisplayName("'모두 지키면' 효과 금액은 액션 카테고리를 모두 말할 때만 — 3개 중 2개만 말하고 전체 효과를 붙인 사례 (#144 재측정 J023·J032)")
        void effectScope_requiresAllCategories() {
            FeedbackContext ctx = contextBuilder()
                    .feedbackType(FeedbackType.ALERT)
                    .savingsImpactWon(-322_996)
                    .actions(List.of(
                            new ActionSummary("교통", 21_426, null, 1.0),
                            new ActionSummary("의류신발", 52_734, null, 1.0),
                            new ActionSummary("오락문화", 11_192, null, 1.0)))
                    .build();

            assertThat(renderer.verifyEffectScope(
                            "의류신발은 5만 3천 원, 교통은 2만 1천 원까지만 쓰면 월말 초과를 32만 원에서 24만 원으로 낮출 수 있어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyEffectScope("의류신발·교통·오락문화를 지키면 월말 초과를 32만 원에서 24만 원으로 낮출 수 있어요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyEffectScope("의류신발은 5만 3천 원까지만 써보세요.", ctx))
                    .isTrue(); // 효과 금액 없음
        }

        @Test
        @DisplayName("보정해도 3문장 — 원 응답이 3문장이면 첫 문장을 코드 문장으로 바꾼다 (리뷰 지적)")
        void correction_keepsThreeSentences() {
            String fixed =
                    FeedbackRenderer.withWeekTotalFirst(week36().build(), "이번 주는 덜 썼어요. 기타가 가장 컸어요. 남은 기간 기타를 줄여봐요.");

            assertThat(fixed).isEqualTo("이번 주는 185,533원을 써서 최근 4주 평균보다 134,467원 적게 썼어요. 기타가 가장 컸어요. 남은 기간 기타를 줄여봐요.");
        }

        @Test
        @DisplayName("'이번 달 예산'의 '이번 달'은 속도의 기간이 아니다 — 같은 문장의 속도 구절도 검사 (리뷰 지적)")
        void pace_monthBudgetIsNotMonthScope() {
            FeedbackContext ctx = week36().build();

            assertThat(renderer.verifyPace("이번 주는 이번 달 예산의 19%를 썼고, 평소보다 빠른 속도였어요.", ctx))
                    .isFalse();
            assertThat(renderer.verifyMonthPace("이번 주는 이번 달 예산의 19%를 썼고, 평소보다 빠른 속도였어요.", ctx))
                    .isTrue(); // 이번 달 속도 구절이 아니므로 이번 달 검사 대상 아님
        }

        @Test
        @DisplayName("'예산 안에 들어온다'는 액션을 다 지켜 초과가 없어질 때, 모든 액션을 말할 때만 (리뷰 지적)")
        void withinBudget_requiresAllActions() {
            FeedbackContext covered = contextBuilder()
                    .savingsImpactWon(-200_000)
                    .actions(List.of(
                            new ActionSummary("기타", 120_000, null, 1.0), new ActionSummary("식대", 80_000, null, 1.0)))
                    .build();
            FeedbackContext remaining = contextBuilder()
                    .savingsImpactWon(-300_000)
                    .actions(List.of(
                            new ActionSummary("기타", 120_000, null, 1.0), new ActionSummary("식대", 80_000, null, 1.0)))
                    .build();

            assertThat(renderer.verifyEffectScope("기타에서 120,000원만 줄이면 이번 달 예산 안에 들어와요.", covered))
                    .isFalse();
            assertThat(renderer.verifyEffectScope("기타와 식대를 지키면 이번 달 예산 안에 들어와요.", covered))
                    .isTrue();
            assertThat(renderer.verifyEffectScope("기타와 식대를 지키면 이번 달 예산 안에 들어와요.", remaining))
                    .isFalse();
        }

        @Test
        @DisplayName("효과 금액은 효과를 말한 문장 안에서만 판정 — 같은 금액의 다른 문장은 오탐하지 않는다 (리뷰 지적)")
        void effectScope_onlyEffectSentence() {
            // 전체 초과 300,000 − 액션 합 150,000 = 150,000 → 이번 주 총지출과 우연히 같음
            FeedbackContext ctx = contextBuilder()
                    .weekTotalWon(150_000)
                    .savingsImpactWon(-300_000)
                    .actions(List.of(
                            new ActionSummary("기타", 100_000, null, 1.0), new ActionSummary("식대", 50_000, null, 1.0)))
                    .build();

            assertThat(renderer.verifyEffectScope("이번 주는 150,000원을 썼어요. 기타를 줄여봐요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyEffectScope("기타는 줄이고, 식대는 그대로 두면 월말 초과를 15만 원으로 낮출 수 있어요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyEffectScope("식대는 그대로. 기타를 줄이면 월말 초과를 15만 원으로 낮출 수 있어요.", ctx))
                    .isFalse();
        }

        @Test
        @DisplayName("'평소보다'가 수식하는 금액만 검사 — 이번 주 총지출이 지난주 차이와 같아도 오탐하지 않는다 (리뷰 지적)")
        void usual_onlyModifiedAmount() {
            // 이번 주 100,000 / 지난주 200,000(차이 100,000) / 직전 4주 평균 140,000(차이 40,000)
            FeedbackContext ctx = contextBuilder()
                    .weekTotalWon(100_000)
                    .prevWeekTotalWon(200_000)
                    .recent4WeekAvgWon(140_000)
                    .build();

            assertThat(renderer.verifyUsual("이번 주는 100,000원을 써서 평소보다 40,000원 적게 썼어요.", ctx))
                    .isTrue();
            assertThat(renderer.verifyUsual("이번 주는 평소보다 10만 원 적게 썼어요.", ctx)).isFalse();
        }

        @Test
        @DisplayName("템플릿 폴백도 절약 액션의 예상 지출과 상한을 보여 준다 (리뷰 지적)")
        void template_showsCap() {
            when(llmProvider.getIfAvailable()).thenReturn(null);
            FeedbackContext ctx = week36().savingsImpactWon(-505_523)
                    .actions(List.of(new ActionSummary("기타", 100_000, null, 1.0, 180_000)))
                    .build();

            assertThat(renderer.render(ctx).text()).contains("남은 기간 기타는 예상 18만원 중 8만원까지만 쓰면");
        }

        @Test
        @DisplayName("절약 액션이 있으면 세 번째 문장은 코드가 만든다 — LLM이 세 문장을 써도 앞 두 문장만 쓰고 뒤에 붙인다 (#146)")
        void actionSentence_isBuiltByCode() {
            LlmClient client = mock(LlmClient.class);
            when(llmProvider.getIfAvailable()).thenReturn(client);
            when(client.complete(anyString(), anyString()))
                    .thenReturn(
                            "이번 주는 185,533원을 써서 최근 4주 평균보다 134,467원 적게 썼어요. 기타가 가장 컸어요. 기타는 줄이고 식대는 그대로 두면 4만 원으로 낮출 수 있어요.");
            FeedbackContext ctx = week36().savingsImpactWon(-300_000)
                    .remainingDaysInMonth(23)
                    .actions(List.of(
                            new ActionSummary("기타", 215_261, null, 1.0, 430_522),
                            new ActionSummary("식대", 28_550, null, 1.0, 309_350)))
                    .build();

            assertThat(renderer.render(ctx).text())
                    .isEqualTo("이번 주는 185,533원을 써서 최근 4주 평균보다 134,467원 적게 썼어요. 기타가 가장 컸어요."
                            + " 남은 23일 동안 기타는 21만 5천 원까지만, 식대는 28만 원까지만 쓰면 월말 초과가 30만 원에서 5만 6천 원으로 줄어요.");
        }

        @Test
        @DisplayName("코드 문장: 초과를 다 덮으면 예산 안, 상한을 모르면 줄일 금액, 천 원 단위 내림 (#146)")
        void actionSentence_formats() {
            FeedbackContext covered = contextBuilder()
                    .savingsImpactWon(-100_000)
                    .remainingDaysInMonth(0)
                    .actions(List.of(new ActionSummary("기타", 100_000, null, 1.0)))
                    .build();

            assertThat(FeedbackRenderer.actionSentence(covered)).isEqualTo("남은 기간 기타는 10만 원 덜 쓰면 이번 달 예산 안에 들어와요.");
            assertThat(FeedbackRenderer.koWon(215_999, false)).isEqualTo("21만 5천 원");
            assertThat(FeedbackRenderer.koWon(215_500, true)).isEqualTo("21만 6천 원");
            assertThat(FeedbackRenderer.koWon(900, false)).isEqualTo("900원");
        }

        @Test
        @DisplayName("'가장 컸다'가 가장 큰 한 건이 아닌 카테고리에 붙으면 거절 (#146)")
        void largest_onlyOnTopTransaction() {
            FeedbackContext ctx = week36().weekHighlights(List.of(
                            new FeedbackRenderer.TransactionHighlight(
                                    java.time.LocalDate.of(2026, 5, 3), "식대", 130_000, null, null),
                            new FeedbackRenderer.TransactionHighlight(
                                    java.time.LocalDate.of(2026, 5, 4), "기타", 50_000, null, null)))
                    .build();

            assertThat(renderer.verifyLargest("식대 한 건이 가장 컸어요.", ctx)).isTrue();
            assertThat(renderer.verifyLargest("기타에서 결제가 가장 컸어요.", ctx)).isFalse();
            assertThat(renderer.verifyLargest("식대가 가장 컸고, 기타도 늘었어요.", ctx)).isTrue();
            // 비교 대상이 앞에 나와도 '가장 컸다'의 대상이 가장 큰 한 건이면 거절하지 않는다
            assertThat(renderer.verifyLargest("기타보다 식대 한 건이 가장 컸어요.", ctx)).isTrue();
            assertThat(renderer.verifyLargest("식대보다 기타 결제가 가장 컸어요.", ctx)).isFalse();
        }

        @Test
        @DisplayName("가장 큰 한 건은 주목 거래 목록이 아니라 따로 받은 이번 주 최대 거래로 검증 (CodeRabbit)")
        void largest_usesSeparateWeekLargest() {
            // 주목 거래에는 기타 5만 원만 있고, 메모·감정이 없는 식대 20만 원은 목록에서 빠져 있다
            FeedbackContext base = week36().weekHighlights(List.of(new FeedbackRenderer.TransactionHighlight(
                            java.time.LocalDate.of(2026, 5, 4), "기타", 50_000, null, null)))
                    .build();
            // 따로 받지 않으면 주목 거래 중 가장 큰 건(기타)이 기준이라, 기타에 붙인 '가장 컸다'가 통과한다
            assertThat(renderer.verifyLargest("기타에서 결제가 가장 컸어요.", base)).isTrue();

            FeedbackContext ctx = withWeekLargest(
                    base,
                    new FeedbackRenderer.TransactionHighlight(
                            java.time.LocalDate.of(2026, 5, 3), "식대", 200_000, null, null));
            assertThat(renderer.verifyLargest("식대 한 건이 가장 컸어요.", ctx)).isTrue();
            assertThat(renderer.verifyLargest("기타에서 결제가 가장 컸어요.", ctx)).isFalse();
        }

        private static FeedbackContext withWeekLargest(FeedbackContext c, FeedbackRenderer.TransactionHighlight top) {
            return new FeedbackContext(
                    c.predictedMonthEndWon(),
                    c.availableBudgetWon(),
                    c.savingsImpactWon(),
                    c.burnRate(),
                    c.pacing(),
                    c.hasAnomaly(),
                    c.anomalyCategoryName(),
                    c.anomalyMagnitude(),
                    c.anomalyWeeklyAmountWon(),
                    c.anomalyConfidence(),
                    c.anomalyEmotion(),
                    c.additionalHighAnomalies(),
                    c.weekEmotion(),
                    c.weekTotalWon(),
                    c.prevWeekTotalWon(),
                    c.complianceRate(),
                    c.overspendCategories(),
                    c.actions(),
                    c.weekHighlights(),
                    c.feedbackType(),
                    c.bandWidth(),
                    c.overallConfidence(),
                    c.anomalyBasis(),
                    c.anomalyWeeklyCount(),
                    c.remainingDaysInMonth(),
                    c.recent4WeekAvgWon(),
                    top);
        }

        @Test
        @DisplayName("마크다운 제목과 단어 안에 섞인 영문·그리스 문자는 거절 (#140 J153·J189)")
        void format_rejectsHeadingAndGarbled() {
            assertThat(renderer.verifyFormat("# 이번 주 소비 피드백\n이번 주는 18만 원을 썼어요."))
                    .isFalse();
            assertThat(renderer.verifyFormat("다른 카테goπ리 절제를 해봐요.")).isFalse();
            assertThat(renderer.verifyFormat("다른 카테go리 절제를 해봐요.")).isFalse();
            assertThat(renderer.verifyFormat("FBS 출금과 GS25 결제가 많았어요.")).isTrue();
            assertThat(renderer.verifyFormat("기타 지출이 1위예요. #1 원인은 기타예요.")).isTrue();
        }
    }

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
        private int anomalyWeeklyCount = 0;
        private int remainingDaysInMonth = 0;
        private long recent4WeekAvgWon = 0L;

        FeedbackContextBuilder pacing(com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult v) {
            this.pacing = v;
            return this;
        }

        FeedbackContextBuilder remainingDaysInMonth(int v) {
            this.remainingDaysInMonth = v;
            return this;
        }

        FeedbackContextBuilder weekTotalWon(long v) {
            this.weekTotalWon = v;
            return this;
        }

        FeedbackContextBuilder prevWeekTotalWon(long v) {
            this.prevWeekTotalWon = v;
            return this;
        }

        FeedbackContextBuilder availableBudgetWon(long v) {
            this.availableBudgetWon = v;
            return this;
        }

        FeedbackContextBuilder recent4WeekAvgWon(long v) {
            this.recent4WeekAvgWon = v;
            return this;
        }

        FeedbackContextBuilder anomalyEmotion(String v) {
            this.anomalyEmotion = v;
            return this;
        }

        FeedbackContextBuilder weekEmotion(FeedbackRenderer.WeekEmotionSummary v) {
            this.weekEmotion = v;
            return this;
        }

        FeedbackContextBuilder anomalyWeeklyCount(int v) {
            this.anomalyWeeklyCount = v;
            return this;
        }

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
                    anomalyBasis,
                    anomalyWeeklyCount,
                    remainingDaysInMonth,
                    recent4WeekAvgWon);
        }
    }
}
