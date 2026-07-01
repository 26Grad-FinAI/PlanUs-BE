package com.planus.backend.domain.aifeedback.rag;

import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import com.planus.backend.domain.aifeedback.core.Confidence;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * [5] 원인 가설 + 신뢰도.
 *  - RAG로 과거 유사 메모(선례)를 검색 → 그 '원문'들을 LLM에 넘겨 원인 문장을 생성(렌더러에서).
 *  - 신뢰도는 규칙 밴드(이상치 배수 + 강한 선례 수). LLM 자기보고 % 미사용.
 *  - 메모가 없으면 원인을 주장하지 않음(LOW, 선례 0) — 오귀인 방지(실험에서 POINT 0/30 검증).
 */
@Service
public class HypothesisService {

    private final MemoVectorStore store;
    private final ExpenseRepository expenseRepo;
    private final AiFeedbackProperties p;

    /**
     * @param store       메모 벡터 저장소 (유사 메모 검색)
     * @param expenseRepo 선례 원문 메모 조회용 리포지토리
     * @param p           RAG 설정 프로퍼티
     */
    public HypothesisService(MemoVectorStore store, ExpenseRepository expenseRepo, AiFeedbackProperties p) {
        this.store = store; this.expenseRepo = expenseRepo; this.p = p;
    }

    /**
     * @param anomalyRep   이상치 대표 거래(메모·감정 보유 가능)
     * @param magnitude    이상치 강도(배수/z)
     * @param categoryName 카테고리명(쿼리 가중)
     * @param before       이 날짜 이전의 선례만 검색(이벤트 자신 제외)
     */
    public Hypothesis infer(Expense anomalyRep, double magnitude, String categoryName, LocalDate before) {
        boolean hasMemo = anomalyRep.getMemo() != null && !anomalyRep.getMemo().isBlank();
        if (!hasMemo) {
            // 메모 없음 → 원인 단서 없음. 원인 주장 금지.
            return new Hypothesis(List.of(), Confidence.LOW, 0, magnitude, false);
        }
        String query = store.buildText(categoryName, anomalyRep.getEmotion(), anomalyRep.getMemo());
        List<MemoVectorStore.Hit> hits = store.retrieve(anomalyRep.getUserId(), query, before, p.getRagTopK());

        List<MemoVectorStore.Hit> strong = hits.stream()
                .filter(h -> h.score() >= p.getRagSimThreshold()).toList();
        int strongN = strong.size();

        // 선례 원문 메모 해결(DATA-02: 벡터 스토어엔 원문 없음 → expense에서 조회)
        // findAllById 순서 보존: ids 순서(유사도 순) → Map으로 조회 후 재조립
        List<Long> ids = strong.stream().map(MemoVectorStore.Hit::expenseId).toList();
        List<String> precedentMemos = new ArrayList<>();
        if (!ids.isEmpty()) {
            Map<Long, Expense> byId = new HashMap<>();
            for (Expense e : expenseRepo.findAllById(ids)) byId.put(e.getId(), e);
            for (Long id : ids) {
                Expense e = byId.get(id);
                if (e != null && e.getMemo() != null && !e.getMemo().isBlank())
                    precedentMemos.add(e.getMemo());
            }
        }
        Confidence conf = Confidence.of(magnitude, strongN, p.getConfHighMag(), p.getConfHighPrecedents());
        return new Hypothesis(precedentMemos, conf, strongN, magnitude, true);
    }

    /** precedentMemos를 LLM에 넘겨 원인 문장 생성. confidence가 LOW면 단독 노출 금지. */
    public record Hypothesis(List<String> precedentMemos, Confidence confidence,
                             int strongPrecedents, double magnitude, boolean hasCue) {}
}
