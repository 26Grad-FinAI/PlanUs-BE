package com.planus.backend.domain.aifeedback.ai;

import com.planus.backend.domain.aifeedback.action.ActionPlan;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Spring AI ChatClient를 사용하여 기존 절약 액션을 행동 수준으로 강화한다.
 *
 * <p>Tool Calling으로 {@link SavingsAnalysisTools}의 소비 패턴 데이터를 조회하고,
 * Structured Output으로 {@link EnrichedAction} 목록을 반환한다.
 *
 * <p>Spring AI 호출 실패 또는 ChatClient 미설정 시 {@link #toBasicEnriched(List)}로
 * 기존 ActionPlan을 단순 변환하여 파이프라인 안정성을 유지한다.
 */
@Component
public class ActionEnricher {

    private static final Logger log = LoggerFactory.getLogger(ActionEnricher.class);

    private static final String SYSTEM_PROMPT =
            """
            당신은 개인 재무 코치입니다. 사용자의 과소비 카테고리에 대해 행동 수준의 절약 제안을 생성합니다.

            제공된 도구를 사용하여 각 카테고리의 소비 패턴(빈도, 요일, 감정)을 분석하세요.

            각 카테고리에 대해 다음을 포함한 EnrichedAction을 생성하세요:
            - actionType: "REDUCE_FREQUENCY" (빈도 줄이기), "REDUCE_UNIT_PRICE" (건당 금액 줄이기), "LIMIT_IMPULSE" (충동 소비 억제) 중 하나
            - currentBehavior: 현재 행동 패턴을 구체적 수치로 설명 (예: "주 4.2회 외식")
            - targetBehavior: 목표 행동을 구체적으로 설명 (예: "주 2회로 감소")
            - contextInsight: 요일/감정 패턴에서 발견한 인사이트 (예: "금요일 저녁 외식이 전체의 60%")
            - behaviorFeasibility: 행동 변화의 실현 가능성 (0.0~1.0)

            반드시 JSON 배열로 응답하세요. categoryId, reductionWon, dominantFactor, feasibilityScore는 입력값을 그대로 사용하세요.
            """;

    @Nullable
    private final ChatClient chatClient;

    @Nullable
    private final SavingsAnalysisTools tools;

    public ActionEnricher(@Nullable ChatClient.Builder chatClientBuilder, @Nullable SavingsAnalysisTools tools) {
        this.chatClient = chatClientBuilder != null ? chatClientBuilder.build() : null;
        this.tools = tools;
    }

    /**
     * 기존 ActionPlan 목록을 Spring AI로 행동 수준으로 강화한다.
     *
     * <p>ChatClient가 설정되지 않은 경우 기본 변환으로 fallback한다.
     *
     * @param userId  사용자 ID
     * @param actions 기존 절약 액션 목록
     * @return 강화된 액션 목록
     */
    public List<EnrichedAction> enrich(Long userId, List<ActionPlan> actions) {
        if (chatClient == null || tools == null) {
            log.info("Spring AI ChatClient 미설정, 기본 actions 사용: userId={}", userId);
            return toBasicEnriched(actions);
        }
        String userPrompt = buildUserPrompt(userId, actions);
        return chatClient
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .tools(tools)
                .call()
                .entity(new ParameterizedTypeReference<>() {});
    }

    /**
     * Spring AI 실패 시 기존 ActionPlan을 단순 변환한 EnrichedAction 목록을 반환한다.
     *
     * @param actions 기존 절약 액션 목록
     * @return 기본 강화 액션 목록 (행동 필드는 null)
     */
    public List<EnrichedAction> toBasicEnriched(List<ActionPlan> actions) {
        return actions.stream()
                .map(a -> new EnrichedAction(
                        a.categoryId(),
                        a.reductionWon(),
                        a.dominantFactor(),
                        a.feasibilityScore(),
                        null,
                        null,
                        null,
                        null,
                        a.feasibilityScore()))
                .toList();
    }

    private String buildUserPrompt(Long userId, List<ActionPlan> actions) {
        String actionList = actions.stream()
                .map(a -> String.format(
                        "- categoryId: %d, reductionWon: %d, dominantFactor: %s, feasibilityScore: %.2f",
                        a.categoryId(), a.reductionWon(), a.dominantFactor(), a.feasibilityScore()))
                .collect(Collectors.joining("\n"));

        return String.format(
                """
                사용자 ID: %d
                분석 대상 절약 액션 목록:
                %s

                각 카테고리에 대해 도구를 사용하여 빈도, 요일, 감정 패턴을 조회한 뒤 행동 수준의 절약 제안을 생성하세요.
                """,
                userId, actionList);
    }
}
