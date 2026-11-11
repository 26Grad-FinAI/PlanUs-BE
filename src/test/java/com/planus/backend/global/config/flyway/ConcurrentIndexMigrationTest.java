package com.planus.backend.global.config.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

class ConcurrentIndexMigrationTest {

    @Nested
    @DisplayName("lock_timeout 값 검증")
    class LockTimeoutValidation {

        @ParameterizedTest
        @ValueSource(strings = {"30s", "500ms", "1min", "3", " 10s "})
        @DisplayName("양수 값은 허용한다")
        void acceptsPositiveValues(String value) {
            assertThat(ConcurrentIndexMigration.normalizeLockTimeout(value)).isEqualTo(value.trim());
        }

        @Test
        @DisplayName("값이 없으면 기본값을 쓴다")
        void defaultsWhenMissing() {
            assertThat(ConcurrentIndexMigration.normalizeLockTimeout(null))
                    .isEqualTo(ConcurrentIndexMigration.DEFAULT_LOCK_TIMEOUT);
            assertThat(ConcurrentIndexMigration.normalizeLockTimeout("  "))
                    .isEqualTo(ConcurrentIndexMigration.DEFAULT_LOCK_TIMEOUT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0s", "0ms", "00min"})
        @DisplayName("0은 PostgreSQL에서 타임아웃 비활성화이므로 거부한다")
        void rejectsZero(String value) {
            assertThatThrownBy(() -> ConcurrentIndexMigration.normalizeLockTimeout(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("0");
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "-1s", "1h", "5s'; SELECT 1; --"})
        @DisplayName("형식에 맞지 않는 값은 거부한다")
        void rejectsInvalidFormat(String value) {
            assertThatThrownBy(() -> ConcurrentIndexMigration.normalizeLockTimeout(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @Testcontainers
    @DisplayName("INVALID 인덱스 정리 대상")
    class DropIfInvalidTarget {

        @Container
        static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
                        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("planus")
                .withUsername("planus")
                .withPassword("planus");

        @BeforeEach
        void resetSchema() throws Exception {
            exec(
                    "DROP SCHEMA IF EXISTS other CASCADE",
                    "DROP TABLE IF EXISTS public.t",
                    "CREATE TABLE public.t (id int)",
                    "CREATE SCHEMA other",
                    "CREATE TABLE other.t (id int)");
        }

        @Test
        @DisplayName("다른 스키마의 같은 이름 INVALID 인덱스 때문에 search_path의 VALID 인덱스를 지우지 않는다")
        void doesNotDropValidIndexBecauseOfSameNameInvalidIndexInOtherSchema() throws Exception {
            exec(
                    "CREATE UNIQUE INDEX idx_probe ON public.t (id)",
                    "CREATE UNIQUE INDEX idx_probe ON other.t (id)",
                    "UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'other.idx_probe'::regclass");

            runDropIfInvalid("idx_probe");

            assertThat(validity("public.idx_probe")).as("public의 VALID 인덱스는 유지").isEqualTo("true");
            assertThat(validity("other.idx_probe")).as("다른 스키마 인덱스는 건드리지 않음").isEqualTo("false");
        }

        @Test
        @DisplayName("search_path의 INVALID 인덱스는 삭제한다")
        void dropsInvalidIndexOnSearchPath() throws Exception {
            exec(
                    "CREATE UNIQUE INDEX idx_probe ON public.t (id)",
                    "UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'public.idx_probe'::regclass");

            runDropIfInvalid("idx_probe");

            assertThat(validity("public.idx_probe")).isNull();
        }

        @Test
        @DisplayName("search_path의 VALID 인덱스는 삭제하지 않는다")
        void keepsValidIndexOnSearchPath() throws Exception {
            exec("CREATE UNIQUE INDEX idx_probe ON public.t (id)");

            runDropIfInvalid("idx_probe");

            assertThat(validity("public.idx_probe")).isEqualTo("true");
        }

        private void runDropIfInvalid(String indexName) throws Exception {
            ClassicConfiguration config = new ClassicConfiguration();
            config.setPlaceholders(Map.of(ConcurrentIndexMigration.LOCK_TIMEOUT_PLACEHOLDER, "5s"));
            try (Connection c = connection()) {
                c.setAutoCommit(true);
                new V999__DropIfInvalidProbe(indexName).migrate(new Context() {
                    @Override
                    public Configuration getConfiguration() {
                        return config;
                    }

                    @Override
                    public Connection getConnection() {
                        return c;
                    }
                });
            }
        }

        /** 인덱스가 없으면 null, 있으면 "true"/"false". */
        private String validity(String qualifiedName) throws Exception {
            try (Connection c = connection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT i.indisvalid::text FROM pg_index i"
                            + " WHERE i.indexrelid = to_regclass('" + qualifiedName + "')")) {
                return rs.next() ? rs.getString(1) : null;
            }
        }

        private void exec(String... sqls) throws Exception {
            try (Connection c = connection();
                    Statement s = c.createStatement()) {
                for (String sql : sqls) s.execute(sql);
            }
        }

        private Connection connection() throws Exception {
            return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        }
    }

    /** dropIfInvalid만 실행하는 검증용 마이그레이션 — db.migration 패키지 밖이라 Flyway가 실행하지 않는다. */
    static class V999__DropIfInvalidProbe extends ConcurrentIndexMigration {
        private final String indexName;

        V999__DropIfInvalidProbe(String indexName) {
            this.indexName = indexName;
        }

        @Override
        protected void migrateConcurrently(Statement stmt) throws Exception {
            dropIfInvalid(stmt, indexName);
        }
    }
}
