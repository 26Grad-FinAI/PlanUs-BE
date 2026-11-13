package com.planus.backend.domain.aifeedback.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * LLM 숫자 후검증 측정 도구 (테스트 전용).
 *
 * <p>{@link #wrap(LlmClient)}으로 실제 LLM 클라이언트를 감싸 {@link FeedbackRenderer}에 주입하면, 렌더 1회마다 LLM 호출 응답·지연·예외를
 * 기록한다. 운영 코드는 변경하지 않는다.
 *
 * <p>판정 원리: 렌더러는 숫자 후검증을 통과한 응답을 그대로 반환한다. 따라서
 * <ul>
 *   <li>최종 텍스트가 k번째 응답과 같으면 → k차 시도에서 통과 (앞선 k-1개 응답은 검증에서 거절)
 *   <li>어느 응답과도 다르고 예외·빈 응답이 있었으면 → 템플릿 폴백(오류/빈 응답)
 *   <li>그 외 → 템플릿 폴백(검증 실패, 모든 응답 거절)
 * </ul>
 *
 * <p>사용법: 렌더마다 {@link #begin()} → {@code renderer.render(ctx)} → {@link #end(String, String)}, 마지막에
 * {@link #print(String)}.
 */
public final class LlmRenderMeter {

    private final List<String> currentCalls = new ArrayList<>();
    private int currentErrors;
    private String currentPrompt;
    private Predicate<String> currentLegacyCheck;
    private int passedOnlyByNewRule;

    private int renders;
    private int totalCalls;
    private int responses;
    private int fallbackVerify;
    private int fallbackError;
    private final Map<Integer, Integer> passedAtAttempt = new TreeMap<>();
    private final List<Long> callMillis = new ArrayList<>();
    private final List<String> rejected = new ArrayList<>();
    /** 검증을 통과해 사용자에게 나간 LLM 응답. 검증기가 놓친 오류(누락)를 사람이 조사할 대상. */
    private final List<String> accepted = new ArrayList<>();
    /** 컨텍스트별 프롬프트 (라벨 → 프롬프트). 응답의 숫자가 맞는지 사람이 대조할 근거. */
    private final Map<String, String> prompts = new LinkedHashMap<>();

    /** 실제 LLM 클라이언트를 감싸 호출을 기록하는 데코레이터를 반환한다. */
    public LlmClient wrap(LlmClient delegate) {
        return (systemPrompt, userPrompt) -> {
            currentPrompt = userPrompt;
            long start = System.nanoTime();
            try {
                String out = delegate.complete(systemPrompt, userPrompt);
                currentCalls.add(out);
                return out;
            } catch (RuntimeException e) {
                currentErrors++;
                throw e;
            } finally {
                callMillis.add((System.nanoTime() - start) / 1_000_000);
            }
        };
    }

    /** 렌더 1회 측정을 시작한다. */
    public void begin() {
        begin(null);
    }

    /**
     * 렌더 1회 측정을 시작한다.
     *
     * @param passesUnderLegacyRule 만원 단위 표현 허용(#111) 이전 규칙으로도 통과하는지 판정하는 함수. 통과 응답 중 이 함수가 false인 것은
     *                              "새 규칙으로 통과"로 표시한다. null이면 구분하지 않는다.
     */
    public void begin(Predicate<String> passesUnderLegacyRule) {
        currentCalls.clear();
        currentErrors = 0;
        currentPrompt = null;
        currentLegacyCheck = passesUnderLegacyRule;
    }

    /** 주간 피드백용 이전 규칙 판정 함수. */
    public static Predicate<String> legacyRule(FeedbackRenderer renderer, FeedbackRenderer.FeedbackContext ctx) {
        return text -> renderer.verifyNumbers(text, ctx, false);
    }

    /** 월말 결산용 이전 규칙 판정 함수. */
    public static Predicate<String> legacyRule(FeedbackRenderer renderer, FeedbackRenderer.MonthEndContext ctx) {
        return text -> renderer.verifyMonthEndNumbers(text, ctx, false);
    }

    /**
     * 렌더 1회 측정을 종료하고 판정 결과를 누적한다.
     *
     * @param finalText 렌더러가 반환한 최종 텍스트
     * @param label     출력용 식별자 (예: "제22주차")
     */
    public void end(String finalText, String label) {
        end(finalText, label, 0);
    }

    /**
     * 같은 컨텍스트를 반복 렌더할 때 사용한다. 거절 응답에는 반복 번호가 붙고(예: "제22주차 r2"), 프롬프트는 컨텍스트당 한 번만 기록된다.
     *
     * @param finalText 렌더러가 반환한 최종 텍스트
     * @param label     컨텍스트 식별자 (예: "제22주차")
     * @param rep       반복 번호 (1부터). 0이면 번호를 붙이지 않는다.
     */
    public void end(String finalText, String label, int rep) {
        renders++;
        totalCalls += currentCalls.size() + currentErrors;
        responses += (int) currentCalls.stream().filter(LlmRenderMeter::hasText).count();

        int acceptedIdx = currentCalls.lastIndexOf(finalText);
        if (acceptedIdx >= 0) {
            passedAtAttempt.merge(acceptedIdx + 1, 1, Integer::sum);
        } else if (currentErrors > 0 || (!currentCalls.isEmpty() && !hasText(currentCalls.getLast()))) {
            fallbackError++;
        } else {
            fallbackVerify++;
        }

        String renderLabel = rep > 0 ? label + " r" + rep : label;
        if (currentPrompt != null) prompts.putIfAbsent(label, currentPrompt);
        if (acceptedIdx >= 0) {
            boolean newRuleOnly = currentLegacyCheck != null && !currentLegacyCheck.test(finalText);
            if (newRuleOnly) passedOnlyByNewRule++;
            accepted.add("[" + renderLabel + " / 시도 " + (acceptedIdx + 1) + (newRuleOnly ? " | 새 규칙으로 통과" : "") + "] "
                    + finalText);
        }
        String outcome = acceptedIdx >= 0 ? (acceptedIdx + 1) + "차 통과" : "폴백";
        int rejectedUntil = acceptedIdx >= 0 ? acceptedIdx : currentCalls.size();
        for (int i = 0; i < rejectedUntil; i++) {
            if (!hasText(currentCalls.get(i))) continue;
            rejected.add("[" + renderLabel + " / 시도 " + (i + 1) + " | 최종: " + outcome + "] " + currentCalls.get(i));
        }
    }

    /** 누적 집계를 표준 출력으로 내보낸다. */
    public void print(String title) {
        System.out.printf("%n=== %s — 숫자 후검증 집계 ===%n", title);
        if (renders == 0) {
            System.out.println("렌더 없음 — 집계 생략");
            return;
        }
        int firstPass = passedAt(1);
        int passed = llmTextUsed();
        System.out.printf("렌더 수               : %d%n", renders);
        System.out.printf("LLM 호출 수           : %d (렌더당 평균 %.2f회)%n", totalCalls, (double) totalCalls / renders);
        System.out.printf(
                "LLM 응답 단위 불일치율 : %d/%d = %.1f%% (빈 응답·예외 제외)%n",
                rejected.size(), responses, pct(rejected.size(), responses));
        System.out.printf("1차 시도 통과          : %d/%d = %.1f%%%n", firstPass, renders, pct(firstPass, renders));
        passedAtAttempt.forEach((k, v) -> {
            if (k > 1) System.out.printf("%d차 시도에서 통과       : %d%n", k, v);
        });
        System.out.printf("최종 LLM 문장 사용      : %d/%d = %.1f%%%n", passed, renders, pct(passed, renders));
        System.out.printf("  └ 새 규칙으로만 통과  : %d (만원 단위 표현 허용 #111이 없었다면 거절)%n", passedOnlyByNewRule);
        System.out.printf("템플릿 폴백(검증 실패)  : %d%n", fallbackVerify);
        System.out.printf("템플릿 폴백(오류/빈 응답): %d%n", fallbackError);

        List<Long> sorted = callMillis.stream().sorted().toList();
        if (!sorted.isEmpty()) {
            LongSummaryStatistics s = sorted.stream().mapToLong(Long::longValue).summaryStatistics();
            System.out.printf(
                    "LLM 호출 지연(ms)       : avg %.0f / p50 %d / p95 %d / max %d%n",
                    s.getAverage(), percentile(sorted, 0.50), percentile(sorted, 0.95), s.getMax());
        }

        System.out.printf("%n--- 검증에서 거절된 LLM 응답 %d건 ---%n", rejected.size());
        rejected.forEach(r -> System.out.println(r.replace("\n", " ⏎ ")));
        System.out.printf("%n--- 검증을 통과한 LLM 응답 %d건 (누락 조사 대상) ---%n", accepted.size());
        accepted.forEach(a -> System.out.println(a.replace("\n", " ⏎ ")));
        System.out.printf("%n--- 컨텍스트별 프롬프트 %d건 (응답 숫자 대조용) ---%n", prompts.size());
        prompts.forEach((label, prompt) -> System.out.printf("[%s]%n%s%n", label, prompt));
    }

    // ── 조회 (판정 로직 검증용) ──

    /** 이번 렌더에서 LLM 호출이 있었는지. LOW_DATA처럼 LLM 없이 끝나는 렌더를 집계에서 빼는 용도. */
    public boolean hasCallsInCurrentRender() {
        return !currentCalls.isEmpty() || currentErrors > 0;
    }

    public int renders() {
        return renders;
    }

    public int totalCalls() {
        return totalCalls;
    }

    public int passedAt(int attempt) {
        return passedAtAttempt.getOrDefault(attempt, 0);
    }

    public int llmTextUsed() {
        return passedAtAttempt.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int fallbackVerify() {
        return fallbackVerify;
    }

    public int fallbackError() {
        return fallbackError;
    }

    public int rejectedCount() {
        return rejected.size();
    }

    public List<String> rejectedLines() {
        return List.copyOf(rejected);
    }

    public int passedOnlyByNewRule() {
        return passedOnlyByNewRule;
    }

    public List<String> acceptedLines() {
        return List.copyOf(accepted);
    }

    public int responses() {
        return responses;
    }

    // ── 내부 ──

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static double pct(int num, int den) {
        return den == 0 ? 0.0 : 100.0 * num / den;
    }

    /** nearest-rank 백분위수. */
    private static long percentile(List<Long> sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
