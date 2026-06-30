package com.planus.backend.domain.aifeedback.rag;

/**
 * 텍스트 → 벡터. 기본 구현(HashingEmbeddingClient)은 외부 키 없이 동작(실험과 동일한 어휘 유사도).
 * 품질이 필요하면 Voyage/OpenAI 임베딩으로 교체(인터페이스만 구현).
 */
public interface EmbeddingClient {
    /**
     * 텍스트를 고정 차원 벡터로 변환한다.
     *
     * @param text 임베딩할 텍스트
     * @return 정규화된 실수 벡터
     */
    float[] embed(String text);
}
