-- =====================================================================
-- V5__add_missing_indexes.sql
-- budget_snapshot_category 인덱스·유니크 제약 + memo_question_queue 복합 인덱스 추가.
-- =====================================================================

-- budget_snapshot_category: snapshot_id FK 인덱스 + (snapshot_id, category_id) 유니크 제약
CREATE INDEX idx_bsc_snapshot ON budget_snapshot_category(snapshot_id);
CREATE UNIQUE INDEX uk_bsc_snapshot_category ON budget_snapshot_category(snapshot_id, category_id);

-- memo_question_queue: countPendingForWeek 쿼리 커버링 인덱스
CREATE INDEX idx_mqq_user_week_status ON memo_question_queue(user_id, question_week, status);
