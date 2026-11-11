package com.planus.backend.global.config.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * CONCURRENTLY 인덱스 작업 규칙을 사람이 아니라 테스트가 지키도록 강제한다 (#109).
 *
 * <ul>
 *   <li>CONCURRENTLY를 쓰는 Java 마이그레이션은 {@link ConcurrentIndexMigration}을 상속해야 한다 (lock_timeout·INVALID 정리 보장).
 *   <li>SQL 마이그레이션에는 CONCURRENTLY를 쓰지 않는다 — Flyway가 비트랜잭션으로 실행하더라도 lock_timeout 보호를 받지 못한다.
 * </ul>
 */
class ConcurrentIndexMigrationConventionTest {

    private static final String MIGRATION_ROOT = "db/migration/";
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern SQL_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Test
    @DisplayName("CONCURRENTLY를 쓰는 Java 마이그레이션은 모두 ConcurrentIndexMigration을 상속한다")
    void javaMigrationsUsingConcurrently_extendConcurrentIndexMigration() throws Exception {
        List<String> scanned = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        for (Resource resource : resolver.getResources("classpath*:" + MIGRATION_ROOT + "**/*.class")) {
            Class<?> type = Class.forName(classNameOf(resource));
            if (!JavaMigration.class.isAssignableFrom(type) || type.isInterface()) continue;
            if (!usesConcurrently(type)) continue;
            scanned.add(type.getSimpleName());
            if (!ConcurrentIndexMigration.class.isAssignableFrom(type)) violations.add(type.getName());
        }

        assertThat(scanned).as("검사 대상이 0개면 스캔 경로가 잘못된 것").contains("V30__add_expense_indexes_concurrently");
        assertThat(violations)
                .as("CONCURRENTLY를 쓰는 마이그레이션은 ConcurrentIndexMigration을 상속해야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("SQL 마이그레이션에는 CONCURRENTLY를 쓰지 않는다 (주석 제외)")
    void sqlMigrations_doNotUseConcurrently() throws Exception {
        Resource[] sqlFiles = resolver.getResources("classpath*:" + MIGRATION_ROOT + "**/*.sql");
        List<String> violations = new ArrayList<>();

        for (Resource resource : sqlFiles) {
            String sql;
            try (InputStream in = resource.getInputStream()) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String code = SQL_LINE_COMMENT
                    .matcher(SQL_BLOCK_COMMENT.matcher(sql).replaceAll(""))
                    .replaceAll("");
            if (code.toUpperCase(Locale.ROOT).contains("CONCURRENTLY")) violations.add(resource.getFilename());
        }

        assertThat(sqlFiles).as("SQL 마이그레이션 스캔 결과").isNotEmpty();
        assertThat(violations)
                .as("CONCURRENTLY는 ConcurrentIndexMigration을 상속한 Java 마이그레이션으로 작성한다")
                .isEmpty();
    }

    @Test
    @DisplayName("검출기 자체 검증: CONCURRENTLY를 쓰면서 상속하지 않은 클래스를 위반으로 잡는다")
    void detector_flagsUnprotectedConcurrentMigration() throws Exception {
        assertThat(usesConcurrently(UnprotectedFixture.class)).isTrue();
        assertThat(ConcurrentIndexMigration.class.isAssignableFrom(UnprotectedFixture.class))
                .isFalse();
        assertThat(usesConcurrently(PlainFixture.class)).isFalse();
    }

    /** 클래스 파일의 상수 풀에 CONCURRENTLY 문자열 리터럴이 있는지 확인한다. */
    private static boolean usesConcurrently(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("클래스 파일을 찾을 수 없음: " + resource);
            String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return bytes.toUpperCase(Locale.ROOT).contains("CONCURRENTLY");
        }
    }

    private static String classNameOf(Resource resource) throws IOException {
        String uri = resource.getURI().toString();
        String relative = uri.substring(uri.lastIndexOf(MIGRATION_ROOT));
        return relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
    }

    // 검출기 검증용 픽스처 — db.migration 패키지 밖이므로 Flyway가 실행하지 않는다.
    static class UnprotectedFixture extends BaseJavaMigration {
        @Override
        public void migrate(org.flywaydb.core.api.migration.Context context) throws Exception {
            context.getConnection().createStatement().execute("CREATE INDEX CONCURRENTLY idx_x ON t (id)");
        }
    }

    static class PlainFixture extends BaseJavaMigration {
        @Override
        public void migrate(org.flywaydb.core.api.migration.Context context) throws Exception {
            context.getConnection().createStatement().execute("CREATE INDEX idx_x ON t (id)");
        }
    }
}
