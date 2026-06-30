package com.planus.backend.domain.aifeedback.llm;

/** LLM 텍스트 생성. 기본 구현은 Anthropic Messages API. 다른 공급자는 이 인터페이스만 구현해 교체. */
public interface LlmClient {
    /**
     * 시스템·사용자 프롬프트로 텍스트를 생성한다.
     *
     * @param systemPrompt 시스템 프롬프트
     * @param userPrompt   사용자 프롬프트
     * @return 생성된 텍스트
     */
    String complete(String systemPrompt, String userPrompt);
}
