package com.planus.backend.domain.aifeedback.rag;

/**
 * 텍스트 → 벡터. 기본 구현(HashingEmbeddingClient)은 외부 키 없이 동작(실험과 동일한 어휘 유사도).
 * 품질이 필요하면 Voyage/OpenAI 임베딩으로 교체(인터페이스만 구현).
 */
public interface EmbeddingClient {
    float[] embed(String text);
}
