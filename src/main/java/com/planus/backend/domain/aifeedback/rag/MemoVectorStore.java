package com.planus.backend.domain.aifeedback.rag;

import com.planus.backend.domain.aifeedback.entity.Expense;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * 벡터DB(pgvector) 기반 메모 검색.
 *  - 저장/검색을 pgvector 네이티브 SQL로 수행(코사인 거리 연산자 <=>, HNSW 인덱스).
 *  - DATA-02: memo_embedding 엔 벡터+참조(expense_id)만. 원문 메모는 expense.memo 에.
 *  - 규모가 더 커지면 동일 인터페이스로 전용 벡터 DB(Qdrant 등)로 교체 가능.
 */
@Service
public class MemoVectorStore {

    private final EmbeddingClient embedder;
    private final JdbcTemplate jdbc;

    /**
     * @param embedder 텍스트 임베딩 클라이언트
     * @param jdbc     pgvector SQL 실행용 JdbcTemplate
     */
    public MemoVectorStore(EmbeddingClient embedder, JdbcTemplate jdbc) {
        this.embedder = embedder;
        this.jdbc = jdbc;
    }

    /** 메모 있는 지출 임베딩 적재. 카테고리·감정을 함께 인덱싱(검색 품질↑). */
    public void index(Expense e, String categoryName) {
        if (e.getMemo() == null || e.getMemo().isBlank()) return;
        String vec = toVectorLiteral(embedder.embed(buildText(categoryName, e.getEmotion(), e.getMemo())));
        jdbc.update(
            "INSERT INTO memo_embedding (expense_id, user_id, category_id, emotion, embedding, ref_date) " +
            "VALUES (?, ?, ?, ?, CAST(? AS vector), ?) " +
            "ON CONFLICT (expense_id) DO NOTHING",
            e.getId(), e.getUserId(), e.getCategoryId(), e.getEmotion(), vec, Date.valueOf(e.getDate()));
    }

    /** 같은 사용자의 과거(before 이전) 메모 중 코사인 유사 상위 k건. DB가 직접 검색. */
    public List<Hit> retrieve(Long userId, String queryText, LocalDate before, int k) {
        String q = toVectorLiteral(embedder.embed(queryText));
        return jdbc.query(
            "SELECT expense_id, category_id, emotion, " +
            "       1 - (embedding <=> CAST(? AS vector)) AS score " +
            "FROM memo_embedding " +
            "WHERE user_id = ? AND ref_date < ? " +
            "ORDER BY embedding <=> CAST(? AS vector) " +
            "LIMIT ?",
            (rs, i) -> new Hit(
                    rs.getDouble("score"),
                    rs.getLong("expense_id"),
                    (Integer) (rs.getObject("category_id") == null ? null : rs.getInt("category_id")),
                    rs.getString("emotion")),
            q, userId, Date.valueOf(before), q, k);
    }

    /** 적재된 임베딩 총 개수(백필·검증용). */
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM memo_embedding", Long.class);
        return n == null ? 0 : n;
    }

    /** 카테고리를 2회 반복해 가중(감정이 여러 상황에 공유될 때 카테고리로 구분 — 실험에서 검증). */
    public String buildText(String categoryName, String emotion, String memo) {
        return categoryName + " " + categoryName + " "
                + (emotion == null ? "" : emotion) + " " + (memo == null ? "" : memo);
    }

    /** float[] → pgvector 리터럴 "[v1,v2,...]" */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        sb.append(']');
        return sb.toString();
    }

    public record Hit(double score, Long expenseId, Integer categoryId, String emotion) {}
}
