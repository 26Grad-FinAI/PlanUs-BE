-- expense 테이블: type 필터를 포함하는 집계 쿼리 8개의 성능 개선
-- 대상: sumExpensesByPeriod, sumVariableExpensesByPeriod, countByUserIdAndPeriod,
--       sumIncomeByPeriod, sumExpensesGroupByCategory, sumIncomeGroupByMonth,
--       sumExpensesGroupByMonth, sumExpensesGroupByMonthAndCategory
-- 기존 idx_expense_user_date (user_id, expense_date)는 type 미포함 쿼리용으로 유지
CREATE INDEX idx_expense_user_type_date
    ON expense (user_id, type, expense_date);

-- expense 테이블: 카테고리별 거래 조회 성능 개선
-- 대상: findByUserIdAndCategoryIdAndExpenseDateBetween (CauseSignalAssembler)
CREATE INDEX idx_expense_user_category_date
    ON expense (user_id, category_id, expense_date);

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

-- expense 테이블: 반복 지출 스케줄러 성능 개선 (부분 인덱스)
-- 대상: findByRecurringAndTypeAndSourceExpenseIdIsNull (월별 반복 지출 자동 등록)
-- NOTE: 반복 지출 로직이 원본 기준인지 최신 복사본 기준인지 별도 검토 필요
CREATE INDEX idx_expense_recurring_origin
    ON expense (type)
    WHERE is_recurring = true AND source_expense_id IS NULL;

-- expense 테이블: 예정 지출 만료 배치 성능 개선 (부분 인덱스)
-- 대상: clearExpiredPlanned (예정일 경과 시 is_planned=false로 전환하는 UPDATE)
CREATE INDEX idx_expense_planned_date
    ON expense (expense_date)
    WHERE is_planned = true;

-- password_reset_code 테이블: 기존 (email) 단일 인덱스를 (email, verified) 복합 인덱스로 교체
-- 대상: findByEmailAndVerifiedFalse, findByEmailAndVerifiedTrue, findByEmailAndCodeAndVerifiedFalse
DROP INDEX IF EXISTS idx_password_reset_code_email;
CREATE INDEX idx_prc_email_verified
    ON password_reset_code (email, verified);
