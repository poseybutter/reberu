package reberu.core.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.support.Uuids;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ActivityLogCleanupTest {

    private static final Instant NOW = Instant.parse("2027-11-09T03:17:00Z");
    private static final LocalDateTime CUTOFF = LocalDateTime.ofInstant(NOW.minus(Duration.ofDays(365)), ZoneOffset.UTC);

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    private ActivityLogCleanup cleanup;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM org_activity_log").update();
        jdbc.sql("DELETE FROM team_activity_log").update();
        cleanup = new ActivityLogCleanup(dataSource, transactions, new AuditProperties(Duration.ofDays(365)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("TEN-LOG-04: 보관 기간이 지난 기록만 지우고 경계 시각의 기록은 남김")
    void deletesOnlyExpired(CapturedOutput output) {
        String expiredOrg = insert("org_activity_log", CUTOFF.minusNanos(1_000));
        String keptOrg = insert("org_activity_log", CUTOFF);
        String expiredTeam = insert("team_activity_log", CUTOFF.minusDays(30));
        String keptTeam = insert("team_activity_log", CUTOFF.plusDays(1));

        int deleted = cleanup.deleteExpired();

        assertThat(deleted).isEqualTo(2);
        assertThat(exists("org_activity_log", expiredOrg)).isFalse();
        assertThat(exists("org_activity_log", keptOrg)).isTrue();
        assertThat(exists("team_activity_log", expiredTeam)).isFalse();
        assertThat(exists("team_activity_log", keptTeam)).isTrue();
        assertThat(output.getOut())
                .contains("활동 기록 정리: org_activity_log occurred_at < " + CUTOFF + "Z, 1건")
                .contains("활동 기록 정리: team_activity_log occurred_at < " + CUTOFF + "Z, 1건");
    }

    @Test
    @DisplayName("한 번에 지우는 묶음보다 많은 기록도 모두 정리")
    void deletesInBatches() {
        int rows = ActivityLogCleanup.BATCH_SIZE * 2 + 1;
        for (int i = 0; i < rows; i++) {
            insert("team_activity_log", CUTOFF.minusDays(1));
        }

        int deleted = cleanup.deleteExpired();

        assertThat(deleted).isEqualTo(rows);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM team_activity_log").query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("한 표 정리가 실패해도 지운 건수를 남기고 다른 표 정리는 계속")
    void failureInOneTableDoesNotStopOthers(CapturedOutput output) {
        for (int i = 0; i < ActivityLogCleanup.BATCH_SIZE + 1; i++) {
            insert("org_activity_log", CUTOFF.minusDays(1));
        }
        String expiredTeam = insert("team_activity_log", CUTOFF.minusDays(1));
        ActivityLogCleanup failing = new ActivityLogCleanup(dataSource, transactions,
                new AuditProperties(Duration.ofDays(365)), Clock.fixed(NOW, ZoneOffset.UTC)) {
            private int orgBatches;

            @Override
            int deleteBatch(String table, LocalDateTime threshold) {
                if (table.equals("org_activity_log") && ++orgBatches == 2) {
                    throw new IllegalStateException("정리 실패 재현");
                }
                return super.deleteBatch(table, threshold);
            }
        };

        int deleted = failing.deleteExpired();

        assertThat(deleted).isEqualTo(ActivityLogCleanup.BATCH_SIZE + 1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM org_activity_log").query(Integer.class).single()).isOne();
        assertThat(exists("team_activity_log", expiredTeam)).isFalse();
        assertThat(output.getOut())
                .contains("활동 기록 정리 중단: org_activity_log occurred_at < " + CUTOFF + "Z, "
                        + ActivityLogCleanup.BATCH_SIZE + "건 삭제 후 실패")
                .contains("활동 기록 정리: team_activity_log occurred_at < " + CUTOFF + "Z, 1건");
    }

    @Test
    @DisplayName("TEN-LOG-04: 보관 기간 365일 미만 설정 거부")
    void rejectsShortRetention() {
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(364)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(365).minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuditProperties(null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new AuditProperties(Duration.ofDays(730)).retention()).isEqualTo(Duration.ofDays(730));
    }

    private String insert(String table, LocalDateTime occurredAt) {
        String owner = table.startsWith("org") ? "org_id" : "team_id";
        String id = Uuids.v7().toString();
        jdbc.sql("INSERT INTO " + table + " (id, " + owner + ", action, target_type, target_id, occurred_at)"
                        + " VALUES (:id, :owner, 'TEAM_CREATED', 'TEAM', :target, :at)")
                .param("id", id)
                .param("owner", UUID.randomUUID().toString())
                .param("target", UUID.randomUUID().toString())
                .param("at", occurredAt)
                .update();
        return id;
    }

    private boolean exists(String table, String id) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE id = :id")
                .param("id", id).query(Integer.class).single() == 1;
    }

}
