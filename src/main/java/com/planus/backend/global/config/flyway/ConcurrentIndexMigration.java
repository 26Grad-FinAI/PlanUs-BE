package com.planus.backend.global.config.flyway;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * CREATE/DROP INDEX CONCURRENTLY(CIC) 마이그레이션의 공통 베이스.
 *
 * <ul>
 *   <li>CIC는 트랜잭션 안에서 실행할 수 없으므로 비트랜잭션 모드로 실행한다.
 *   <li>CIC는 오래된 스냅샷을 쥔 트랜잭션이 끝날 때까지 {@code virtualxid} 잠금을 기다린다. 그 트랜잭션이 이 마이그레이션의
 *       종료를 기다리는 경우(예: Flyway 트랜잭션 advisory lock) 대기 사슬이 DB 밖(애플리케이션)을 거치므로 PostgreSQL deadlock
 *       detector가 감지하지 못하고 무한 대기한다. 세션 {@code lock_timeout}으로 이런 대기를 원인이 담긴 에러로 빠르게 실패시킨다.
 *   <li>CIC가 실패하면 INVALID 인덱스가 남고, 재시도 시 {@code IF NOT EXISTS}가 이를 건너뛰어 "성공했지만 쓰이지 않는 인덱스"가
 *       된다. 생성 전에 {@link #dropIfInvalid}로 정리해야 한다.
 * </ul>
 *
 * <p>lock_timeout은 잠금 대기에만 적용되며 인덱스 빌드 시간 자체는 제한하지 않는다. 기본값은 {@value #DEFAULT_LOCK_TIMEOUT}이며
 * Flyway placeholder {@value #LOCK_TIMEOUT_PLACEHOLDER}로 변경할 수 있다.
 */
public abstract class ConcurrentIndexMigration extends BaseJavaMigration {

    public static final String LOCK_TIMEOUT_PLACEHOLDER = "cicLockTimeout";
    public static final String DEFAULT_LOCK_TIMEOUT = "30s";

    private static final Pattern LOCK_TIMEOUT_FORMAT = Pattern.compile("(\\d+)(ms|s|min)?");

    @Override
    public final boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public final void migrate(Context context) throws Exception {
        String lockTimeout = resolveLockTimeout(context);
        try (Statement stmt = context.getConnection().createStatement()) {
            stmt.execute("SET lock_timeout = '" + lockTimeout + "'");
            Exception failure = null;
            try {
                migrateConcurrently(stmt);
            } catch (Exception e) {
                failure = e;
                throw e;
            } finally {
                // 세션 설정이 커넥션 풀로 반환되는 커넥션에 남지 않도록 되돌린다.
                try {
                    stmt.execute("RESET lock_timeout");
                } catch (SQLException resetFailure) {
                    if (failure == null) throw resetFailure;
                    failure.addSuppressed(resetFailure);
                }
            }
        }
    }

    /** lock_timeout이 설정된 세션에서 CIC 작업을 수행한다. */
    protected abstract void migrateConcurrently(Statement stmt) throws Exception;

    /**
     * 인덱스가 존재하면서 INVALID 상태이면 삭제한다 — 이전 CIC 실행이 중단된 경우 대비.
     *
     * <p>조회는 {@code to_regclass}로 대상을 찾는다. {@code DROP INDEX}와 같은 search_path 규칙으로 이름을 해석하므로, 다른 스키마에
     * 같은 이름의 INVALID 인덱스가 있어도 search_path의 VALID 인덱스를 잘못 삭제하지 않는다.
     */
    protected final void dropIfInvalid(Statement stmt, String indexName) throws SQLException {
        boolean invalid;
        try (PreparedStatement query = stmt.getConnection()
                .prepareStatement("SELECT NOT i.indisvalid FROM pg_index i WHERE i.indexrelid = to_regclass(?)")) {
            query.setString(1, indexName);
            try (ResultSet rs = query.executeQuery()) {
                invalid = rs.next() && rs.getBoolean(1);
            }
        }
        if (invalid) {
            stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS " + indexName);
        }
    }

    private static String resolveLockTimeout(Context context) {
        return normalizeLockTimeout(context.getConfiguration().getPlaceholders().get(LOCK_TIMEOUT_PLACEHOLDER));
    }

    /**
     * placeholder 값을 검증한다. 값이 없으면 기본값을 쓰고, 0은 거부한다 — PostgreSQL에서 {@code lock_timeout = 0}은 타임아웃
     * 비활성화라서 보호 장치가 조용히 꺼진다.
     */
    static String normalizeLockTimeout(String configured) {
        String lockTimeout = configured == null || configured.isBlank() ? DEFAULT_LOCK_TIMEOUT : configured.trim();
        Matcher matcher = LOCK_TIMEOUT_FORMAT.matcher(lockTimeout);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "잘못된 " + LOCK_TIMEOUT_PLACEHOLDER + " 값: '" + lockTimeout + "' (예: 30s, 500ms, 1min)");
        }
        if (matcher.group(1).chars().allMatch(ch -> ch == '0')) {
            throw new IllegalArgumentException(
                    LOCK_TIMEOUT_PLACEHOLDER + "는 0일 수 없습니다 (0은 lock_timeout 비활성화): '" + lockTimeout + "'");
        }
        return lockTimeout;
    }
}
