-- =====================================================================
-- V27: budget_category 중복 안전 정리 + password_reset_code 인덱스 교체
-- 인덱스 생성/삭제는 V30에서 CONCURRENTLY로 별도 처리
-- =====================================================================

-- budget_category 테이블: 중복 행 안전 검사
-- 데이터가 다른 중복이 있으면 마이그레이션을 중단하여 데이터 손실을 방지한다.
DO $$
DECLARE
    conflict_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO conflict_count
    FROM budget_category a
    JOIN budget_category b
      ON a.budget_id = b.budget_id
     AND a.category_id = b.category_id
     AND a.id < b.id
    WHERE a.amount IS DISTINCT FROM b.amount
       OR a.ai_amount IS DISTINCT FROM b.ai_amount
       OR a.user_modified IS DISTINCT FROM b.user_modified;

    IF conflict_count > 0 THEN
        RAISE EXCEPTION
            'budget_category에 데이터가 다른 중복 행 %건이 존재합니다. '
            '수동으로 보존 기준을 결정한 뒤 마이그레이션을 재실행하세요.',
            conflict_count;
    END IF;

    -- 데이터가 동일한 중복만 제거 (가장 작은 id 보존)
    DELETE FROM budget_category
    WHERE id NOT IN (
        SELECT MIN(id)
        FROM budget_category
        GROUP BY budget_id, category_id
    );
END $$;

-- password_reset_code 테이블: 기존 (email) 단일 인덱스를 (email, verified) 복합 인덱스로 교체
-- 대상: findByEmailAndVerifiedFalse, findByEmailAndVerifiedTrue, findByEmailAndCodeAndVerifiedFalse
DROP INDEX IF EXISTS idx_password_reset_code_email;
CREATE INDEX idx_prc_email_verified
    ON password_reset_code (email, verified);
