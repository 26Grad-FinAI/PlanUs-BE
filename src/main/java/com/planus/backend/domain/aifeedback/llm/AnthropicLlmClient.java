package com.planus.backend.domain.aifeedback.llm;

import jakarta.annotation.PostConstruct;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anthropic Messages API 구현. application.yml:
 *   planus.ai.llm.api-key: ${ANTHROPIC_API_KEY}
 *   planus.ai.llm.model:   claude-sonnet-4-5   (보유 모델명으로 설정)
 *
 * <p>API 키 미설정 시 {@link #complete}에서 예외를 던지며,
 * {@link FeedbackRenderer}가 이를 캐치하여 템플릿 폴백을 사용한다.
 */
@Component
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private RestClient client;
    private LlmCallGuard guard;

    @Value("${planus.ai.llm.base-url:https://api.anthropic.com}")
    private String baseUrl;

    /** 응답 한 번을 기다리는 읽기 타임아웃. */
    @Value("${planus.ai.llm.read-timeout-seconds:30}")
    private long readTimeoutSeconds;

    /** 호출 하나(재시도 포함)의 전체 시간 상한. 읽기 타임아웃이 걸리지 않는 정지까지 잡는다 (#124). */
    @Value("${planus.ai.llm.total-timeout-seconds:60}")
    private long totalTimeoutSeconds;

    /** 일시 장애(429·529·5xx·연결 실패) 재시도 횟수. 0이면 재시도하지 않는다. */
    @Value("${planus.ai.llm.max-retries:2}")
    private int maxRetries;

    @Value("${planus.ai.llm.retry-backoff-millis:1000}")
    private long retryBackoffMillis;

    /** 연속 실패가 이 횟수에 이르면 서킷을 연다. 영구 장애(크레딧·키 오류)는 1회로 바로 연다. */
    @Value("${planus.ai.llm.circuit-open-after:5}")
    private int circuitOpenAfter;

    @Value("${planus.ai.llm.circuit-open-seconds:300}")
    private long circuitOpenSeconds;

    @Value("${planus.ai.llm.api-key:}")
    private String apiKey;

    @Value("${planus.ai.llm.model:claude-sonnet-4-5}")
    private String model;

    @Value("${planus.ai.llm.max-tokens:600}")
    private int maxTokens;

    @PostConstruct
    void init() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        guard = new LlmCallGuard(new LlmCallGuard.Settings(
                maxRetries,
                Duration.ofMillis(retryBackoffMillis),
                Duration.ofSeconds(totalTimeoutSeconds),
                circuitOpenAfter,
                Duration.ofSeconds(circuitOpenSeconds)));
        if (apiKey == null || apiKey.isBlank()) {
            String envKey = System.getenv("ANTHROPIC_API_KEY");
            if (envKey != null && !envKey.isBlank()) {
                apiKey = envKey;
                log.info("ANTHROPIC_API_KEY 환경변수에서 직접 로드");
            } else {
                log.warn("planus.ai.llm.api-key 미설정, ANTHROPIC_API_KEY 환경변수도 없음 → LLM 비활성");
            }
        } else {
            log.info("AnthropicLlmClient 초기화 완료 (모델: {})", model);
        }
    }

    /** 마지막 조회 이후의 호출 통계(성공·원인별 실패·평균 지연)를 가져가고 0으로 되돌린다. 배치 종료 로그용 (#124). */
    @Override
    public LlmCallGuard.Stats snapshotStats() {
        return guard.snapshotAndReset();
    }

    /**
     * Anthropic Messages API를 호출하여 텍스트를 생성한다.
     *
     * @param systemPrompt 시스템 프롬프트 (역할·규칙)
     * @param userPrompt   사용자 프롬프트 (분석 재료)
     * @return 생성된 텍스트
     * @throws IllegalStateException API 키가 미설정인 경우
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("planus.ai.llm.api-key 미설정");
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt)));
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = guard.call(() -> client.post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class));
        if (resp == null) return "";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
        if (content == null || content.isEmpty()) return "";
        Object text = content.get(0).get("text");
        return text == null ? "" : text.toString();
    }
}
