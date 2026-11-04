package db.migration;

import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 모든 CONCURRENTLY 인덱스 작업을 비트랜잭션 모드로 실행한다.
 *
 * <p>CREATE/DROP INDEX CONCURRENTLY는 트랜잭션 내부에서 실행할 수 없으므로 Java 마이그레이션으로 분리하여
 * 운영 DB에서 쓰기 차단을 방지한다.
 *
 * <p>이전 실행이 중단되어 INVALID 인덱스가 남아 있으면 먼저 삭제한 뒤 재생성한다.
 */
public class V30__add_expense_indexes_concurrently extends BaseJavaMigration {

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement stmt = context.getConnection().createStatement()) {
            // INVALID 인덱스 정리 — 이전 CONCURRENTLY 실행이 중단된 경우 대비
            dropIfInvalid(stmt, "uk_bc_budget_category");
            dropIfInvalid(stmt, "idx_ur_user_id");
            dropIfInvalid(stmt, "idx_ssi_simulation_id");
            dropIfInvalid(stmt, "idx_expense_user_type_date");
            dropIfInvalid(stmt, "idx_expense_user_category_date");
            dropIfInvalid(stmt, "idx_expense_recurring_origin");
            dropIfInvalid(stmt, "idx_expense_planned_date");

            // budget_category — UNIQUE 인덱스 (중복 방지 + FK 조회 성능 개선)
            stmt.execute("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_bc_budget_category"
                    + " ON budget_category (budget_id, category_id)");

            // user_reactions — 사용자별 반응 조회 성능 개선
            stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ur_user_id" + " ON user_reactions (user_id)");

            // saving_simulation_items — LAZY 로딩 FK 조회 성능 개선
            stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ssi_simulation_id"
                    + " ON saving_simulation_items (simulation_id)");

            // budget_snapshot_category — 중복 인덱스 제거
            // uk_bsc_snapshot_category (snapshot_id, category_id)가 snapshot_id 단일 조회도 커버
            stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS idx_bsc_snapshot");

            // expense (user_id, type, expense_date) — 집계 쿼리 성능 개선
            stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_expense_user_type_date"
                    + " ON expense (user_id, type, expense_date)");

            // expense (user_id, category_id, expense_date) — 카테고리별 거래 조회 성능 개선
            stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_expense_user_category_date"
                    + " ON expense (user_id, category_id, expense_date)");

            // expense 부분 인덱스 — 반복 지출 스케줄러용
            stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_expense_recurring_origin"
                    + " ON expense (type)"
                    + " WHERE is_recurring = true AND source_expense_id IS NULL");

            // expense 부분 인덱스 — 예정 지출 만료 배치용
            stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_expense_planned_date"
                    + " ON expense (expense_date)"
                    + " WHERE is_planned = true");
        }
    }

    /** 인덱스가 존재하면서 INVALID 상태이면 삭제한다. */
    private void dropIfInvalid(Statement stmt, String indexName) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_index i"
                + " JOIN pg_class c ON c.oid = i.indexrelid"
                + " WHERE c.relname = '"
                + indexName
                + "'"
                + " AND NOT i.indisvalid")) {
            if (rs.next()) {
                stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS " + indexName);
            }
        }
    }
}
