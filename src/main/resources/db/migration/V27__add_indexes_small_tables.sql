-- =====================================================================
-- V27: 소규모 테이블 인덱스 추가/제거 + budget_category 중복 정리
-- expense 인덱스는 V27_1에서 CONCURRENTLY로 별도 생성
-- =====================================================================

-- budget_category 테이블: UNIQUE 인덱스 생성 전 중복 행 정리
-- V17 이후 (budget_id, category_id) 조합이 중복 삽입되었을 가능성 대비
DELETE FROM budget_category
WHERE id NOT IN (
    SELECT MIN(id)
    FROM budget_category
    GROUP BY budget_id, category_id
);

-- budget_category 테이블: FK 조회 성능 개선 + 중복 데이터 방지
-- 대상: findByBudgetId, findByBudgetIdAndCategoryId
CREATE UNIQUE INDEX uk_bc_budget_category
    ON budget_category (budget_id, category_id);

-- user_reactions 테이블: 사용자별 반응 조회 성능 개선
-- 대상: findByUserId (ProfileUpdater 민감 카테고리 산출)
CREATE INDEX idx_ur_user_id
    ON user_reactions (user_id);

-- saving_simulation_items 테이블: LAZY 로딩 및 orphanRemoval 시 FK 조회 성능 개선
-- 대상: SavingSimulation.items @OneToMany LAZY 로딩 쿼리
CREATE INDEX idx_ssi_simulation_id
    ON saving_simulation_items (simulation_id);

-- budget_snapshot_category 테이블: 중복 인덱스 제거
-- uk_bsc_snapshot_category (snapshot_id, category_id)가 snapshot_id 단일 조회도 커버
DROP INDEX idx_bsc_snapshot;

-- password_reset_code 테이블: 기존 (email) 단일 인덱스를 (email, verified) 복합 인덱스로 교체
-- 대상: findByEmailAndVerifiedFalse, findByEmailAndVerifiedTrue, findByEmailAndCodeAndVerifiedFalse
DROP INDEX IF EXISTS idx_password_reset_code_email;
CREATE INDEX idx_prc_email_verified
    ON password_reset_code (email, verified);
