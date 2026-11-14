package com.planus.backend.domain.aifeedback.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.planus.backend.domain.aifeedback.action.ActionPlan;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class ActionEnricherTest {

    private ActionEnricher enricher;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);

        enricher = new ActionEnricher(builder, null);
    }

    @Nested
    @DisplayName("cleanText()")
    class CleanText {

        @Test
        @DisplayName("기준을 지키면 앞뒤 공백만 뺀 문구를 그대로 쓴다")
        void keepsValidText() {
            assertThat(ActionEnricher.cleanText(" 이번 달 쇼핑 3번, 한 번에 6만 원꼴 ", ActionEnricher.MAX_CURRENT_LEN))
                    .isEqualTo("이번 달 쇼핑 3번, 한 번에 6만 원꼴");
            assertThat(ActionEnricher.cleanText("사고 싶을 땐 하루 미루고 결정하기", ActionEnricher.MAX_TARGET_LEN))
                    .isEqualTo("사고 싶을 땐 하루 미루고 결정하기");
        }

        @Test
        @DisplayName("null·빈 문자열은 null")
        void blank() {
            assertThat(ActionEnricher.cleanText(null, 25)).isNull();
            assertThat(ActionEnricher.cleanText("   ", 25)).isNull();
        }

        @Test
        @DisplayName("글자 수 상한을 넘으면 null")
        void tooLong() {
            String longText = "토요일에 충동 구매가 발생하여 전체의 대부분을 차지하고 패턴이 뚜렷합니다";
            assertThat(ActionEnricher.cleanText(longText, ActionEnricher.MAX_INSIGHT_LEN))
                    .isNull();
            assertThat(ActionEnricher.cleanText("가".repeat(25), 25)).isNotNull();
            assertThat(ActionEnricher.cleanText("가".repeat(26), 25)).isNull();
        }

        @Test
        @DisplayName("퍼센트가 있으면 null")
        void percent() {
            assertThat(ActionEnricher.cleanText("전체의 53% 차지", 30)).isNull();
            assertThat(ActionEnricher.cleanText("절반 이상이 퍼센트로", 30)).isNull();
        }

        @Test
        @DisplayName("소수가 있으면 null")
        void decimal() {
            assertThat(ActionEnricher.cleanText("주 0.75회 거래", 25)).isNull();
        }

        @Test
        @DisplayName("숫자가 3개 이상이면 null, 2개까지는 허용")
        void tooManyNumbers() {
            assertThat(ActionEnricher.cleanText("3건 중 2건이 충동 소비", 25)).isNotNull();
            assertThat(ActionEnricher.cleanText("3건 중 2건, 100,000원 지출", 25)).isNull();
        }
    }

    @Nested
    @DisplayName("cleanTarget()")
    class CleanTarget {

        @Test
        @DisplayName("실천할 행동은 그대로 쓴다")
        void keepsActions() {
            assertThat(ActionEnricher.cleanTarget("사고 싶을 땐 하루 미루고 결정하기")).isNotNull();
            assertThat(ActionEnricher.cleanTarget("배달은 주말에만 시키기")).isNotNull();
            assertThat(ActionEnricher.cleanTarget("배달은 주 2번만 시키기")).isNotNull();
        }

        @Test
        @DisplayName("수치 목표·결과 표현은 상황 설명이므로 null")
        void rejectsNumericGoals() {
            assertThat(ActionEnricher.cleanTarget("건당 평균 6,500원 이하로 감소")).isNull();
            assertThat(ActionEnricher.cleanTarget("월 1회 이하로 줄이기")).isNull();
            assertThat(ActionEnricher.cleanTarget("충동 구매를 월 2회 미만으로")).isNull();
            assertThat(ActionEnricher.cleanTarget("지출 20% 감소")).isNull();
            assertThat(ActionEnricher.cleanTarget("주 3회 이내로 유지")).isNull();
        }

        @Test
        @DisplayName("기본 형식 검사(글자 수·소수 등)도 함께 적용")
        void appliesBaseRules() {
            assertThat(ActionEnricher.cleanTarget(null)).isNull();
            assertThat(ActionEnricher.cleanTarget("가".repeat(26))).isNull();
            assertThat(ActionEnricher.cleanTarget("주 1.5회만 시키기")).isNull();
        }
    }

    @Nested
    @DisplayName("mergeBehavior()")
    class MergeBehavior {

        private final List<ActionPlan> base = List.of(new ActionPlan(7, 40000, "FREQUENCY", 0.74));

        @Test
        @DisplayName("기준을 지킨 행동 문구는 그대로 쓰고 금액·카테고리는 원본을 쓴다")
        void keepsValid() {
            EnrichedAction llm = new EnrichedAction(
                    7,
                    999,
                    "X",
                    0.1,
                    "LIMIT_IMPULSE",
                    "이번 달 쇼핑 3번, 한 번에 6만 원꼴",
                    "사고 싶을 땐 하루 미루고 결정하기",
                    "주말 충동구매가 대부분이에요",
                    0.8);

            EnrichedAction result = enricher.mergeBehavior(base, List.of(llm)).get(0);

            assertThat(result.reductionWon()).isEqualTo(40000);
            assertThat(result.dominantFactor()).isEqualTo("FREQUENCY");
            assertThat(result.actionType()).isEqualTo("LIMIT_IMPULSE");
            assertThat(result.currentBehavior()).isEqualTo("이번 달 쇼핑 3번, 한 번에 6만 원꼴");
            assertThat(result.targetBehavior()).isEqualTo("사고 싶을 땐 하루 미루고 결정하기");
            assertThat(result.contextInsight()).isEqualTo("주말 충동구매가 대부분이에요");
            assertThat(result.behaviorFeasibility()).isEqualTo(0.8);
        }

        @Test
        @DisplayName("기준을 어긴 필드만 null로 비우고 나머지는 유지한다")
        void blanksOnlyInvalidFields() {
            EnrichedAction llm = new EnrichedAction(
                    7,
                    40000,
                    "FREQUENCY",
                    0.74,
                    "REDUCE_FREQUENCY",
                    "주 0.75회 거래, 건당 평균 62,333원의 고액 지출",
                    "사고 싶을 땐 하루 미루고 결정하기",
                    "토요일에 100,000원의 충동 구매가 발생하여 전체의 53.5%를 차지합니다. 3건 중 2건이 충동 소비로, 충동 구매 패턴이 뚜렷합니다.",
                    0.8);

            EnrichedAction result = enricher.mergeBehavior(base, List.of(llm)).get(0);

            assertThat(result.currentBehavior()).isNull();
            assertThat(result.contextInsight()).isNull();
            assertThat(result.targetBehavior()).isEqualTo("사고 싶을 땐 하루 미루고 결정하기");
            assertThat(result.actionType()).isEqualTo("REDUCE_FREQUENCY");
        }

        @Test
        @DisplayName("목표 행동이 수치 목표면 그 필드만 null (#131)")
        void blanksNumericTarget() {
            EnrichedAction llm = new EnrichedAction(
                    7,
                    40000,
                    "FREQUENCY",
                    0.74,
                    "REDUCE_UNIT_PRICE",
                    "이번 달 쇼핑 3번, 한 번에 6만 원꼴",
                    "건당 평균 6,500원 이하로 감소",
                    "주말 충동구매가 대부분이에요",
                    0.8);

            EnrichedAction result = enricher.mergeBehavior(base, List.of(llm)).get(0);

            assertThat(result.targetBehavior()).isNull();
            assertThat(result.currentBehavior()).isEqualTo("이번 달 쇼핑 3번, 한 번에 6만 원꼴");
            assertThat(result.contextInsight()).isEqualTo("주말 충동구매가 대부분이에요");
        }

        @Test
        @DisplayName("알 수 없는 actionType은 null, 실현 가능성은 0~1로 보정")
        void normalizesTypeAndFeasibility() {
            EnrichedAction llm = new EnrichedAction(7, 0, null, 0, "UNKNOWN", null, null, null, 1.7);

            EnrichedAction result = enricher.mergeBehavior(base, List.of(llm)).get(0);

            assertThat(result.actionType()).isNull();
            assertThat(result.behaviorFeasibility()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("LLM이 해당 카테고리를 빼먹으면 기본 변환으로 대체")
        void missingCategoryFallsBack() {
            EnrichedAction result = enricher.mergeBehavior(base, List.of()).get(0);

            assertThat(result.categoryId()).isEqualTo(7);
            assertThat(result.currentBehavior()).isNull();
            assertThat(result.behaviorFeasibility()).isEqualTo(0.74);
        }
    }

    @Nested
    @DisplayName("toBasicEnriched()")
    class ToBasicEnriched {

        @Test
        @DisplayName("ActionPlan을 행동 필드가 null인 EnrichedAction으로 변환한다")
        void convertsWithNullBehaviorFields() {
            List<ActionPlan> actions =
                    List.of(new ActionPlan(7, 40000, "FREQUENCY", 0.74), new ActionPlan(5, 20000, "UNIT_PRICE", 0.65));

            List<EnrichedAction> result = enricher.toBasicEnriched(actions);

            assertThat(result).hasSize(2);

            EnrichedAction first = result.get(0);
            assertThat(first.categoryId()).isEqualTo(7);
            assertThat(first.reductionWon()).isEqualTo(40000);
            assertThat(first.dominantFactor()).isEqualTo("FREQUENCY");
            assertThat(first.feasibilityScore()).isEqualTo(0.74);
            assertThat(first.actionType()).isNull();
            assertThat(first.currentBehavior()).isNull();
            assertThat(first.targetBehavior()).isNull();
            assertThat(first.contextInsight()).isNull();
            assertThat(first.behaviorFeasibility()).isEqualTo(0.74);

            EnrichedAction second = result.get(1);
            assertThat(second.categoryId()).isEqualTo(5);
            assertThat(second.reductionWon()).isEqualTo(20000);
            assertThat(second.behaviorFeasibility()).isEqualTo(0.65);
        }

        @Test
        @DisplayName("빈 목록이면 빈 결과 반환")
        void emptyInput() {
            List<EnrichedAction> result = enricher.toBasicEnriched(List.of());

            assertThat(result).isEmpty();
        }
    }
}
