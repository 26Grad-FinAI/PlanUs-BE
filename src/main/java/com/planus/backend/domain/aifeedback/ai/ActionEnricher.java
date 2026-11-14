package com.planus.backend.domain.aifeedback.ai;

import com.planus.backend.domain.aifeedback.action.ActionPlan;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** currentBehavior 최대 글자 수. */
    static final int MAX_CURRENT_LEN = 25;

    /** targetBehavior 최대 글자 수. */
    static final int MAX_TARGET_LEN = 25;

    /** contextInsight 최대 글자 수. */
    static final int MAX_INSIGHT_LEN = 30;

    /** 한 필드에 허용하는 숫자 개수 (#131). */
    static final int MAX_NUMBERS_PER_FIELD = 2;

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private static final Pattern DECIMAL = Pattern.compile("\\d\\.\\d");

    /**
     * 수치 목표 표현. targetBehavior는 "건당 평균 6,500원 이하로 감소"처럼 목표 수치를 말하면 상황 설명이지 실천할 행동이 아니다 (#131).
     * 숫자가 하나여서 숫자 개수 검사를 통과하므로 따로 거른다.
     */
    private static final Pattern TARGET_NUMBER_GOAL = Pattern.compile("이하|미만|이내|이상|감소|감축|줄어|낮아|절감");

    private static final Set<String> ACTION_TYPES = Set.of("REDUCE_FREQUENCY", "REDUCE_UNIT_PRICE", "LIMIT_IMPULSE");

    private static final String SYSTEM_PROMPT =
            """
            당신은 20대를 위한 친근한 소비 코치입니다. 사용자의 과소비 카테고리에 대해, 카드 한 장에서 바로 읽고 바로 실천할 수 있는 짧은 절약 제안을 만듭니다.

            도구로 각 카테고리의 소비 패턴(빈도, 요일, 감정)을 조회해 무엇이 문제인지 파악하세요. 도구 값은 '이번 달'(이번 달 1일~기준일)과 '평소'(직전 3개월) 기준입니다. 현재 행동은 이번 달 기준으로만 쓰고, 평소는 비교할 때만 씁니다. 서로 다른 기간의 숫자를 한 문장에 섞지 않습니다.
            분석은 직접 계산하지 말고 도구가 준 값을 쓰되, 문장에는 핵심만 풀어 씁니다.

            각 카테고리에 대해 다음을 생성하세요. 필드마다 글자 수 상한이 있고, 넘으면 버려집니다.
            - actionType: "REDUCE_FREQUENCY"(횟수 줄이기), "REDUCE_UNIT_PRICE"(건당 금액 줄이기), "LIMIT_IMPULSE"(충동 소비 억제) 중 하나
            - currentBehavior (25자 이내): 이번 달 소비 습관을 한 줄로. 횟수는 정수, '이번 달' 기준. 예: "이번 달 쇼핑 3번, 한 번에 6만 원꼴"
            - targetBehavior (25자 이내): 사용자가 지금 바로 해볼 수 있는 행동 하나. 상황 설명이나 수치 목표가 아니라 "~하기"로 끝나는 구체적인 행동이어야 합니다.
              좋은 예: "사고 싶을 땐 하루 미루고 결정하기", "배달은 주말에만 시키기", "결제 전에 장바구니에 하루 담아두기"
              나쁜 예(쓰면 안 됨): "건당 평균 6,500원 이하로 감소", "월 1회 이하로 줄이기", "충동 구매 억제" — 목표 수치나 결과만 말하고 무엇을 할지 없음
              '이하', '미만', '이내', '이상', '감소', '감축' 같은 말과 평균·건당 금액 목표는 쓰지 않습니다.
            - contextInsight (30자 이내): 패턴에서 눈에 띄는 점 한 가지. 예: "주말 충동구매가 대부분이에요"
            - behaviorFeasibility: 행동 변화의 실현 가능성 (0.0~1.0)

            문장 규칙:
            - 숫자는 한 필드에 최대 2개. 퍼센트(%)와 소수(0.75회, 3.5배)는 쓰지 않습니다. '주 N회'처럼 주 평균으로 환산하지 말고 이번 달 건수를 그대로 씁니다.
            - 이번 달 거래가 3건 미만이면 비율·패턴을 단정하지 말고 숫자 없이 가볍게 씁니다.
            - '~했습니다' 대신 '~요'체로 친근하게 씁니다. 분석 보고서처럼 쓰지 않습니다.
            - 감정 태그 이름(IMPULSE 등)이나 도구 용어는 그대로 쓰지 않고 일상어로 풀어 씁니다.

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
    public List<EnrichedAction> enrich(Long userId, LocalDate asOf, List<ActionPlan> actions) {
        if (chatClient == null || tools == null) {
            log.info("Spring AI ChatClient 미설정, 기본 actions 사용: userId={}", userId);
            return toBasicEnriched(actions);
        }
        String userPrompt = buildUserPrompt(userId, asOf, actions);
        List<EnrichedAction> llm = chatClient
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .tools(tools)
                .toolContext(Map.of("userId", userId, SavingsAnalysisTools.AS_OF, asOf))
                .call()
                .entity(new ParameterizedTypeReference<List<EnrichedAction>>() {});
        return mergeBehavior(actions, llm);
    }

    /**
     * LLM 응답에서 행동 필드만 가져오고, 금액·카테고리 값은 원본 ActionPlan에서 사용한다.
     *
     * <p>행동 문구는 {@link #cleanText}로 길이·형식을 검사해, 어긴 필드만 null로 비운다 (#131). null은 {@link #toBasicEnriched}와
     * 같은 "문구 없음" 상태라 클라이언트가 이미 처리한다.
     */
    List<EnrichedAction> mergeBehavior(List<ActionPlan> base, List<EnrichedAction> llm) {
        Map<Integer, EnrichedAction> byCat = llm == null
                ? Map.of()
                : llm.stream().collect(Collectors.toMap(EnrichedAction::categoryId, a -> a, (a, b) -> a));
        return base.stream()
                .map(a -> {
                    EnrichedAction e = byCat.get(a.categoryId());
                    if (e == null) {
                        return toBasicEnriched(List.of(a)).get(0);
                    }
                    double bf = Math.max(0.0, Math.min(1.0, e.behaviorFeasibility()));
                    return new EnrichedAction(
                            a.categoryId(),
                            a.reductionWon(),
                            a.dominantFactor(),
                            a.feasibilityScore(),
                            e.actionType() != null && ACTION_TYPES.contains(e.actionType()) ? e.actionType() : null,
                            cleanText(e.currentBehavior(), MAX_CURRENT_LEN),
                            cleanTarget(e.targetBehavior()),
                            cleanText(e.contextInsight(), MAX_INSIGHT_LEN),
                            bf);
                })
                .toList();
    }

    /** 목표 행동 검사: {@link #cleanText} 기준에 더해, "6,500원 이하로 감소" 같은 수치 목표 표현이면 null (#131). */
    static String cleanTarget(String text) {
        String t = cleanText(text, MAX_TARGET_LEN);
        return t == null || TARGET_NUMBER_GOAL.matcher(t).find() ? null : t;
    }

    /**
     * 행동 문구 검사. 비었거나, 글자 수 상한을 넘거나, 퍼센트·소수가 있거나, 숫자가 {@value #MAX_NUMBERS_PER_FIELD}개를 넘으면 null.
     *
     * @return 앞뒤 공백을 뺀 문구, 기준을 어기면 null
     */
    static String cleanText(String text, int maxLen) {
        if (text == null) return null;
        String t = text.strip();
        if (t.isEmpty() || t.length() > maxLen) return null;
        if (t.contains("%") || t.contains("퍼센트") || DECIMAL.matcher(t).find()) return null;
        int numbers = 0;
        Matcher m = NUMBER.matcher(t);
        while (m.find()) numbers++;
        return numbers > MAX_NUMBERS_PER_FIELD ? null : t;
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

    private String buildUserPrompt(Long userId, LocalDate asOf, List<ActionPlan> actions) {
        String actionList = actions.stream()
                .map(a -> String.format(
                        "- categoryId: %d, reductionWon: %d, dominantFactor: %s, feasibilityScore: %.2f",
                        a.categoryId(), a.reductionWon(), a.dominantFactor(), a.feasibilityScore()))
                .collect(Collectors.joining("\n"));

        return String.format(
                """
                사용자 ID: %d
                기준일: %s (이번 달 %s ~ %s)
                분석 대상 절약 액션 목록:
                %s

                각 카테고리에 대해 도구를 사용하여 빈도, 요일, 감정 패턴을 조회한 뒤 행동 수준의 절약 제안을 생성하세요.
                """,
                userId, asOf, asOf.withDayOfMonth(1), asOf, actionList);
    }
}
