-- =====================================================================
-- V29: V28에서 NOT VALID로 추가한 CHECK 제약을 검증한다.
-- VALIDATE CONSTRAINT는 ShareUpdateExclusiveLock만 획득하므로
-- 검증 스캔 중에도 읽기·쓰기가 모두 가능하다.
-- =====================================================================

ALTER TABLE expense VALIDATE CONSTRAINT chk_expense_category;
ALTER TABLE budget_category VALIDATE CONSTRAINT chk_budget_category_category;
ALTER TABLE ai_feedbacks VALIDATE CONSTRAINT chk_ai_feedbacks_category;
ALTER TABLE user_reactions VALIDATE CONSTRAINT chk_user_reactions_category;
ALTER TABLE saving_simulation_items VALIDATE CONSTRAINT chk_ssi_category;
ALTER TABLE budget_snapshot_category VALIDATE CONSTRAINT chk_bsc_category;
ALTER TABLE memo_question_queue VALIDATE CONSTRAINT chk_mqq_category;
