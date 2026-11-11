package com.planus.backend.global.config.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Flyway 트랜잭션 advisory lock × CREATE INDEX CONCURRENTLY(V30) 교착의 재현 및 회귀 방지 테스트 (#109).
 *
 * <p>Flyway 9.1.2+ 기본값({@code postgresql.transactional.lock=true})은 advisory lock을 잡은 트랜잭션을 마이그레이션 내내
 * 열어 둔다. V30의 CIC는 그 트랜잭션의 스냅샷이 사라지기를 {@code virtualxid} 잠금으로 기다리고, 그 트랜잭션은 마이그레이션 종료를
 * 기다린다. 대기 사슬이 애플리케이션을 거치므로 PostgreSQL deadlock detector가 감지하지 못한다.
 */
@Testcontainers
class FlywayConcurrentIndexDeadlockTest {

    private static final Logger log = LoggerFactory.getLogger(FlywayConcurrentIndexDeadlockTest.class);

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("planus")
            .withUsername("planus")
            .withPassword("planus");

    private static final String TEST_LOCK_TIMEOUT = "3s";
    private static final Duration HANG_GUARD = Duration.ofSeconds(60);
    private static final String APP_NAME = "flyway-under-test";

    /** V30이 생성해야 하는 인덱스. */
    private static final List<String> V30_INDEXES = List.of(
            "uk_bc_budget_category",
            "idx_ur_user_id",
            "idx_ssi_simulation_id",
            "idx_expense_user_type_date",
            "idx_expense_user_category_date",
            "idx_expense_recurring_origin",
            "idx_expense_planned_date");

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static int dbSequence = 0;

    private String dbName;
    private String dbUrl;

    @BeforeEach
    void createFreshDatabase() throws Exception {
        dbName = "flyway_cic_" + (++dbSequence);
        try (Connection c = adminConnection();
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + dbName);
        }
        dbUrl = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + dbName;
    }

    @AfterEach
    void dropDatabase() throws Exception {
        try (Connection c = adminConnection();
                Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
        }
    }

    @AfterAll
    static void shutdownExecutor() {
        EXECUTOR.shutdownNow();
    }

    @Test
    @DisplayName("transactional-lock=true면 V30 CIC가 Flyway 잠금 트랜잭션을 virtualxid로 대기하다 lock_timeout으로 빠르게 실패한다")
    void transactionalLockTrue_cicWaitsOnFlywayLockSession_andFailsFastWithLockTimeout() throws Exception {
        long start = System.nanoTime();
        Future<MigrationResult> migration = EXECUTOR.submit(() -> migrate(true));

        WaitSnapshot waitSnapshot = awaitVirtualxidWait(migration);
        MigrationResult result = migration.get(HANG_GUARD.toSeconds(), TimeUnit.SECONDS);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        log.info("[#109] transactional-lock=true → {} ({}ms)", result, elapsed.toMillis());
        waitSnapshot.sessions().forEach(row -> log.info("[#109] 대기 스냅샷: {}", row));
        waitSnapshot.progress().forEach(row -> log.info("[#109] CIC 진행 단계: {}", row));

        // 대기 관계 직접 증거: CIC 세션이 virtualxid를 기다리고, 그 blocker는 같은 Flyway의 idle in transaction 세션
        SessionRow cic = waitSnapshot.sessions().stream()
                .filter(row -> "virtualxid".equals(row.waitEvent()))
                .findFirst()
                .orElseThrow();
        assertThat(cic.query()).contains("CREATE").contains("CONCURRENTLY");
        assertThat(cic.blockedBy()).hasSize(1);
        SessionRow blocker = waitSnapshot.sessions().stream()
                .filter(row -> row.pid() == cic.blockedBy().get(0))
                .findFirst()
                .orElseThrow();
        assertThat(blocker.appName()).isEqualTo(APP_NAME);
        assertThat(blocker.state()).isEqualTo("idle in transaction");

        // 멈춘 단계와 대기 대상: CIC는 "오래된 스냅샷 대기" 단계에서 xmin을 쥔 Flyway 잠금 트랜잭션을 기다린다
        ProgressRow progress = waitSnapshot.progress().stream()
                .filter(row -> row.pid() == cic.pid())
                .findFirst()
                .orElseThrow();
        assertThat(progress.phase()).isEqualTo("waiting for old snapshots");
        assertThat(progress.currentLockerPid()).isEqualTo(blocker.pid());
        assertThat(blocker.backendXmin())
                .as("blocker가 스냅샷(xmin)을 쥐고 있어야 CIC가 기다린다")
                .isNotNull();

        // 무한 대기가 아니라 원인이 담긴 에러로 실패
        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).contains("lock timeout");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("application.yaml의 transactional-lock 설정으로 전체 마이그레이션이 정상 완료된다 (설정 제거 시 실패하는 회귀 방지)")
    void applicationYamlSetting_completesAllMigrations() throws Exception {
        boolean transactionalLock = transactionalLockFromApplicationYaml();

        MigrationResult result =
                EXECUTOR.submit(() -> migrate(transactionalLock)).get(HANG_GUARD.toSeconds(), TimeUnit.SECONDS);

        log.info("[#109] application.yaml transactional-lock={} → {}", transactionalLock, result);
        assertThat(result.succeeded()).as("마이그레이션 결과: %s", result).isTrue();
        assertThat(indexValidity()).containsOnlyKeys(V30_INDEXES).doesNotContainValue(false);
    }

    @Test
    @DisplayName("CIC가 실패해 INVALID 인덱스가 남아도 재시도 시 정리 후 재생성되어 모두 VALID가 된다")
    void retryAfterLockTimeoutFailure_dropsInvalidIndexAndRecreates() throws Exception {
        MigrationResult first = EXECUTOR.submit(() -> migrate(true)).get(HANG_GUARD.toSeconds(), TimeUnit.SECONDS);
        Map<String, Boolean> afterFailure = indexValidity();
        log.info("[#109] 1차(true) → {}, 인덱스 상태 {}", first, afterFailure);

        assertThat(first.succeeded()).isFalse();
        assertThat(afterFailure).as("실패한 CIC는 INVALID 인덱스를 남긴다").containsValue(false);

        MigrationResult retry = EXECUTOR.submit(() -> migrate(false)).get(HANG_GUARD.toSeconds(), TimeUnit.SECONDS);
        Map<String, Boolean> afterRetry = indexValidity();
        log.info("[#109] 재시도(false) → {}, 인덱스 상태 {}", retry, afterRetry);

        assertThat(retry.succeeded()).as("재시도 결과: %s", retry).isTrue();
        assertThat(afterRetry).containsOnlyKeys(V30_INDEXES).doesNotContainValue(false);
    }

    // ───────────────────────── helpers ─────────────────────────

    private MigrationResult migrate(boolean transactionalLock) {
        try {
            Flyway.configure()
                    .dataSource(dbUrl + "?ApplicationName=" + APP_NAME, PG.getUsername(), PG.getPassword())
                    .locations("classpath:db/migration")
                    .placeholders(Map.of(ConcurrentIndexMigration.LOCK_TIMEOUT_PLACEHOLDER, TEST_LOCK_TIMEOUT))
                    .configuration(Map.of("flyway.postgresql.transactional.lock", String.valueOf(transactionalLock)))
                    .load()
                    .migrate();
            return MigrationResult.success();
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            return MigrationResult.failure(root.getMessage());
        }
    }

    /** 마이그레이션이 끝나기 전에 virtualxid 대기 세션이 보이면 그 시점의 세션 목록과 CIC 진행 단계를 반환한다. */
    private WaitSnapshot awaitVirtualxidWait(Future<?> migration) throws Exception {
        long deadline = System.nanoTime() + HANG_GUARD.toNanos();
        while (!migration.isDone() && System.nanoTime() < deadline) {
            WaitSnapshot snapshot = snapshot();
            if (snapshot.sessions().stream().anyMatch(row -> "virtualxid".equals(row.waitEvent()))) {
                return snapshot;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("virtualxid 대기 세션을 관찰하지 못함 (migration done=" + migration.isDone() + ")");
    }

    /** 세션 목록과 CIC 진행 단계를 같은 트랜잭션(같은 시점의 통계 스냅샷)에서 조회한다. */
    private WaitSnapshot snapshot() throws Exception {
        List<SessionRow> sessions = new ArrayList<>();
        List<ProgressRow> progress = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(dbUrl, PG.getUsername(), PG.getPassword());
                Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            try (ResultSet rs = s.executeQuery("SELECT pid, application_name, state, wait_event_type, wait_event,"
                    + " pg_blocking_pids(pid) AS blocked_by, backend_xid::text AS xid, backend_xmin::text AS xmin,"
                    + " query"
                    + " FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND pid <> pg_backend_pid()")) {
                while (rs.next()) {
                    Integer[] blockedBy = (Integer[]) rs.getArray("blocked_by").getArray();
                    sessions.add(new SessionRow(
                            rs.getInt("pid"),
                            rs.getString("application_name"),
                            rs.getString("state"),
                            rs.getString("wait_event_type"),
                            rs.getString("wait_event"),
                            List.of(blockedBy),
                            rs.getString("xid"),
                            rs.getString("xmin"),
                            rs.getString("query")));
                }
            }
            try (ResultSet rs = s.executeQuery("SELECT pid, phase, lockers_total, lockers_done, current_locker_pid"
                    + " FROM pg_stat_progress_create_index")) {
                while (rs.next()) {
                    progress.add(new ProgressRow(
                            rs.getInt("pid"),
                            rs.getString("phase"),
                            rs.getLong("lockers_total"),
                            rs.getLong("lockers_done"),
                            rs.getInt("current_locker_pid")));
                }
            }
            c.rollback();
        }
        return new WaitSnapshot(sessions, progress);
    }

    private Map<String, Boolean> indexValidity() throws Exception {
        Map<String, Boolean> validity = new java.util.TreeMap<>();
        try (Connection c = DriverManager.getConnection(dbUrl, PG.getUsername(), PG.getPassword());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT c.relname, i.indisvalid FROM pg_index i"
                        + " JOIN pg_class c ON c.oid = i.indexrelid"
                        + " WHERE c.relname IN ('" + String.join("','", V30_INDEXES) + "')")) {
            while (rs.next()) {
                validity.put(rs.getString(1), rs.getBoolean(2));
            }
        }
        return validity;
    }

    private static boolean transactionalLockFromApplicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();
        // 설정이 없으면 Flyway 기본값(true)
        return Boolean.parseBoolean(properties.getProperty("spring.flyway.postgresql.transactional-lock", "true"));
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private record MigrationResult(boolean succeeded, String error) {
        static MigrationResult success() {
            return new MigrationResult(true, null);
        }

        static MigrationResult failure(String error) {
            return new MigrationResult(false, error);
        }
    }

    private record SessionRow(
            int pid,
            String appName,
            String state,
            String waitEventType,
            String waitEvent,
            List<Integer> blockedBy,
            String backendXid,
            String backendXmin,
            String query) {}

    private record ProgressRow(int pid, String phase, long lockersTotal, long lockersDone, int currentLockerPid) {}

    private record WaitSnapshot(List<SessionRow> sessions, List<ProgressRow> progress) {}
}
