package com.planus.backend.domain.aifeedback.llm;

/** LLM 텍스트 생성. 기본 구현은 Anthropic Messages API. 다른 공급자는 이 인터페이스만 구현해 교체. */
public interface LlmClient {
    String complete(String systemPrompt, String userPrompt);
}
