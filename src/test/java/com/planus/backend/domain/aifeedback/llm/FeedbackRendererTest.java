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
                    .overspendCategories(List.of(new CategoryOverspend("식대", 191_731, 139_940, cs, 9)))
                    .build();

            assertThat(capturedUserPrompt(ctx))
                    .contains("(이번 주 금액이 평소 주당 금액의 1.7배) — 원인: 횟수 증가")
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
                    .contains("(이 중 이번 주 신규 116,600원, 4건)")
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
            assertThat(prompt).contains("- 식대: 이번 달 누적 초과 +191,731원 (이 중 이번 주 신규 139,940원, 9건) [절약 액션 대상 아님]");
            assertThat(prompt).contains("- 기타: 이번 달 누적 초과 +204,821원 (이 중 이번 주 신규 58,250원, 4건)\n");
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
        @DisplayName("시스템 규칙: 배수의 대상 명시 + 소수 첫째 자리까지 그대로")
        void systemPrompt_multiplierRules() {
            FeedbackContext ctx =
                    contextBuilder().feedbackType(FeedbackType.ALERT).build();

            assertThat(capturedSystemPrompt(ctx))
                    .contains("'한 건' 배수는 그 거래에만 붙이고")
                    .contains("배수는 제공된 값을 소수 첫째 자리까지 그대로 쓴다")
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
        private int anomalyWeeklyCount = 0;

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
                    anomalyWeeklyCount);
        }
    }
}
