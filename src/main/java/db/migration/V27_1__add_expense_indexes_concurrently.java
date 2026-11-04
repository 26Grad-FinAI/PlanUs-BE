package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * expense 테이블 인덱스를 CONCURRENTLY로 생성한다.
 *
 * <p>CREATE INDEX CONCURRENTLY는 트랜잭션 내부에서 실행할 수 없으므로
 * Java 마이그레이션으로 분리하여 비트랜잭션 모드로 실행한다.
 * 이를 통해 운영 DB에서 인덱스 생성 중 쓰기 차단을 방지한다.
 */
public class V27_1__add_expense_indexes_concurrently extends BaseJavaMigration {

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement stmt = context.getConnection().createStatement()) {
            // expense (user_id, type, expense_date) — 집계 쿼리 8개 성능 개선
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
}
