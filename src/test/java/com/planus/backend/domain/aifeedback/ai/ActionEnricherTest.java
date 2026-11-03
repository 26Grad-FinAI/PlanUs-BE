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
