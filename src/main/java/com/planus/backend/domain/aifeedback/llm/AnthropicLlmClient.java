package com.planus.backend.domain.aifeedback.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 구현. application.yml:
 *   planus.ai.llm.api-key: ${ANTHROPIC_API_KEY}
 *   planus.ai.llm.model:   claude-sonnet-4-5   (보유 모델명으로 설정)
 * 다른 공급자를 쓰면 LlmClient 를 직접 구현(이 빈은 @ConditionalOnMissingBean 으로 비활성).
 */
@Component
@ConditionalOnMissingBean(LlmClient.class)
public class AnthropicLlmClient implements LlmClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient client;

    {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        client = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .requestFactory(factory)
                .build();
    }

    @Value("${planus.ai.llm.api-key:}") private String apiKey;
    @Value("${planus.ai.llm.model:claude-sonnet-4-5}") private String model;
    @Value("${planus.ai.llm.max-tokens:600}") private int maxTokens;

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
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("planus.ai.llm.api-key 미설정");
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt)));
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = client.post().uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
        if (content == null || content.isEmpty()) return "";
        Object text = content.get(0).get("text");
        return text == null ? "" : text.toString();
    }
}
