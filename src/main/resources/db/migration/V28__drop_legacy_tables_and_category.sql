-- =====================================================================
-- V28: 레거시 테이블 정리 + category 테이블 제거
-- 코드에서 사용하지 않는 테이블 8개를 DROP하고,
-- category FK를 CHECK 제약(NOT VALID)으로 대체한다.
-- CHECK 검증은 V29에서 별도 수행하여 테이블 잠금 시간을 최소화한다.
-- =====================================================================

-- -------------------------------------------------------
-- 1단계: 레거시 테이블 DROP (그룹 A — 외부 참조 없음)
-- -------------------------------------------------------
DROP TABLE IF EXISTS inquiry;
DROP TABLE IF EXISTS notification;
DROP TABLE IF EXISTS monthly_summaries;
DROP TABLE IF EXISTS advice_results;
DROP TABLE IF EXISTS peer_comparison_stats;

-- -------------------------------------------------------
-- 2단계: 레거시 테이블 DROP (그룹 B — 순서 필요)
-- user_personas가 persona_templates를 참조하므로 먼저 DROP
-- -------------------------------------------------------
DROP TABLE IF EXISTS user_personas;
DROP TABLE IF EXISTS persona_templates;

-- -------------------------------------------------------
-- 3단계: 활성 테이블의 category FK 제거
-- -------------------------------------------------------
ALTER TABLE expense
    DROP CONSTRAINT IF EXISTS fk_expense_category;

ALTER TABLE budget_category
    DROP CONSTRAINT IF EXISTS fk_budget_category_category;

ALTER TABLE ai_feedbacks
    DROP CONSTRAINT IF EXISTS fk_ai_feedbacks_category;

ALTER TABLE user_reactions
    DROP CONSTRAINT IF EXISTS fk_user_reactions_category;

ALTER TABLE saving_simulation_items
    DROP CONSTRAINT IF EXISTS fk_saving_simulation_items_category;

-- V17에서 추가된 category FK 2개도 제거
ALTER TABLE budget_snapshot_category
    DROP CONSTRAINT IF EXISTS fk_budget_snapshot_category_category;

ALTER TABLE memo_question_queue
    DROP CONSTRAINT IF EXISTS fk_memo_question_queue_category;

-- -------------------------------------------------------
-- 4단계: CHECK 제약을 NOT VALID로 추가 (1~10)
-- NOT VALID: 기존 행을 스캔하지 않으므로 테이블 잠금 최소화
-- 새로 INSERT/UPDATE되는 행에 대해서만 즉시 검증
-- 기존 데이터 검증은 V29에서 VALIDATE CONSTRAINT로 별도 수행
-- -------------------------------------------------------
ALTER TABLE expense
    ADD CONSTRAINT chk_expense_category CHECK (category_id BETWEEN 1 AND 10) NOT VALID;

ALTER TABLE budget_category
    ADD CONSTRAINT chk_budget_category_category CHECK (category_id BETWEEN 1 AND 10) NOT VALID;

ALTER TABLE ai_feedbacks
    ADD CONSTRAINT chk_ai_feedbacks_category CHECK (category_id BETWEEN 1 AND 10) NOT VALID;

ALTER TABLE user_reactions
    ADD CONSTRAINT chk_user_reactions_category CHECK (category_id BETWEEN 1 AND 10) NOT VALID;

ALTER TABLE saving_simulation_items
    ADD CONSTRAINT chk_ssi_category CHECK (category_id BETWEEN 1 AND 10) NOT VALID;

ALTER TABLE budget_snapshot_category
    ADD CONSTRAINT chk_bsc_category CHECK (category_id BETWEEN 1 AND 10) NOT VALID;

ALTER TABLE memo_question_queue
    ADD CONSTRAINT chk_mqq_category CHECK (category_id BETWEEN 1 AND 10) NOT VALID;

-- -------------------------------------------------------
-- 5단계: category 테이블 DROP
-- -------------------------------------------------------
DROP TABLE IF EXISTS category;
