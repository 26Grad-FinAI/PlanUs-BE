package com.planus.backend.domain.aifeedback.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 외부 키 불필요한 기본 임베딩. 문자 n-gram(2~4)을 부호 있는 feature hashing으로 고정 차원 벡터화 후 L2 정규화.
 * 실험에서 쓴 TF-IDF 문자 n-gram 유사도를 근사한다(어휘 겹침 기반 코사인). 더 좋은 의미 임베딩이 필요하면
 * EmbeddingClient를 Voyage/OpenAI 구현으로 교체.
 */
@Component
@ConditionalOnMissingBean(value = EmbeddingClient.class, ignored = HashingEmbeddingClient.class) // 운영 임베딩 빈이 있으면 그쪽 우선
public class HashingEmbeddingClient implements EmbeddingClient {

    private static final int DIM = 2000;  // V2 마이그레이션의 vector(2000)과 일치

    /**
     * 문자 n-gram(2~4) feature hashing으로 텍스트를 2000차원 벡터로 변환한다.
     * 부호 해싱으로 충돌을 상쇄하며, L2 정규화를 적용한다.
     *
     * @param text 임베딩할 텍스트 (null이면 빈 문자열 처리)
     * @return L2 정규화된 2000차원 실수 벡터
     */
    @Override
    public float[] embed(String text) {
        float[] v = new float[DIM];
        if (text == null) text = "";
        String t = " " + text.toLowerCase().trim() + " ";
        for (int n = 2; n <= 4; n++) {
            for (int i = 0; i + n <= t.length(); i++) {
                String g = t.substring(i, i + n);
                int h = g.hashCode();
                int idx = Math.floorMod(h, DIM);
                int sign = ((h >>> 31) & 1) == 0 ? 1 : -1; // 부호 해싱으로 충돌 상쇄
                v[idx] += sign;
            }
        }
        double norm = 0; for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < DIM; i++) v[i] /= norm;
        return v;
    }
}
