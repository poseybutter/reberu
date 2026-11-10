package reberu.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.TestcontainersConfiguration;
import reberu.core.support.Uuids;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActivityLogImmutabilityTest {

    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log");
    private static final Path MAIN_SOURCES = Path.of("src/main");
    private static final Pattern CHANGE_SQL = Pattern.compile(
            "(?i)\\b(UPDATE|DELETE\\s+FROM|TRUNCATE(\\s+TABLE)?|ALTER\\s+TABLE|DROP\\s+TABLE)\\s+[\"`]?(org|team)_activity_log");
    private static final String CLEANUP = "ActivityLogCleanup.java";

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    @DisplayName("T-AUDIT-IMMUTABLE: DB 트리거가 기록 수정 거부")
    void databaseRejectsUpdate() {
        for (String table : TABLES) {
            String id = insert(table);

            assertThatThrownBy(() -> jdbc.sql("UPDATE " + table + " SET after_value = 'x' WHERE id = :id")
                    .param("id", id).update())
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("append-only");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE id = :id AND after_value IS NULL")
                    .param("id", id).query(Integer.class).single()).isOne();
        }
    }

    @Test
    @DisplayName("T-AUDIT-IMMUTABLE: PostgreSQL에서 TRUNCATE 거부")
    void postgresRejectsTruncate() {
        Assumptions.assumeTrue("postgresql".equals(System.getProperty(TestcontainersConfiguration.DB_PROPERTY, "postgresql")));
        for (String table : TABLES) {
            insert(table);

            assertThatThrownBy(() -> jdbc.sql("TRUNCATE " + table).update())
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("append-only");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single()).isOne();
        }
    }

    @Test
    @DisplayName("T-AUDIT-IMMUTABLE: 공개 API는 기록 추가와 조회만 제공, 고치거나 지우는 메서드 없음")
    void publicApiOnlyRecords() {
        List<String> methods = Arrays.stream(ActivityLogs.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertThat(methods).containsExactlyInAnyOrder("recordOrg", "recordTeam", "orgRecordedSince", "recentOrg");
    }

    @Test
    @DisplayName("T-AUDIT-IMMUTABLE: 코드에 기록 수정·삭제 SQL 없음(보관 기간 정리 작업만 예외)")
    void noChangePathInSources() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            offenders = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals(CLEANUP))
                    .filter(this::containsChangeSql)
                    .toList();
        }

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("T-AUDIT-IMMUTABLE: 정리 작업의 삭제는 보관 기간 조건을 함께 씀")
    void cleanupDeletesOnlyByRetention() throws IOException {
        String source;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            Path cleanup = files.filter(path -> path.getFileName().toString().equals(CLEANUP)).findFirst().orElseThrow();
            source = Files.readString(cleanup, StandardCharsets.UTF_8);
        }

        assertThat(source).containsPattern("DELETE FROM \" \\+ table \\+ \" WHERE id IN \\(:ids\\) AND occurred_at < :threshold");
        assertThat(source).doesNotContainPattern("(?i)\"\\s*(UPDATE|TRUNCATE)\\b");
    }

    @Test
    @DisplayName("실패 사례: 수정 SQL 검출 패턴이 실제로 걸림")
    void changeSqlPatternDetects() {
        assertThat(CHANGE_SQL.matcher("jdbc.sql(\"UPDATE org_activity_log SET x = 1\")").find()).isTrue();
        assertThat(CHANGE_SQL.matcher("delete from `team_activity_log` where").find()).isTrue();
        assertThat(CHANGE_SQL.matcher("TRUNCATE TABLE org_activity_log").find()).isTrue();
        assertThat(CHANGE_SQL.matcher("INSERT INTO team_activity_log").find()).isFalse();
    }

    private boolean containsChangeSql(Path path) {
        try {
            return CHANGE_SQL.matcher(Files.readString(path, StandardCharsets.UTF_8)).find();
        }
        catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String insert(String table) {
        String owner = table.startsWith("org") ? "org_id" : "team_id";
        String id = Uuids.v7().toString();
        jdbc.sql("INSERT INTO " + table + " (id, " + owner + ", action, target_type, target_id, occurred_at)"
                        + " VALUES (:id, :owner, 'TEAM_CREATED', 'TEAM', :target, :at)")
                .param("id", id)
                .param("owner", UUID.randomUUID().toString())
                .param("target", UUID.randomUUID().toString())
                .param("at", LocalDateTime.of(2026, 11, 9, 0, 0))
                .update();
        return id;
    }

}
