package reberu.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.support.Uuids;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActivityLogTest {

    private static final UUID ORG = UUID.fromString("0190f2a4-0000-7000-8000-00000000a001");
    private static final UUID TEAM = UUID.fromString("0190f2a4-0000-7000-8000-00000000b001");
    private static final UUID ACTOR = UUID.fromString("0190f2a4-0000-7000-8000-00000000c001");
    private static final UUID TARGET = UUID.fromString("0190f2a4-0000-7000-8000-00000000d001");

    @Autowired
    ActivityLogs activityLogs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM org_activity_log").update();
        jdbc.sql("DELETE FROM team_activity_log").update();
        jdbc.sql("DELETE FROM account WHERE google_sub LIKE 'audit-test-%'").update();
    }

    @Test
    @DisplayName("T-AUDIT-RECORD: 조직·팀 기록에 시각·행위자·행동·대상·전후 값 저장")
    void recordsOrgAndTeamEntries() {
        transactions.executeWithoutResult(status -> {
            activityLogs.recordOrg(ORG, entry(ACTOR, "ORG_SETTINGS_CHANGED", "Asia/Seoul", "UTC"));
            activityLogs.recordTeam(TEAM, entry(null, "TEAM_ROLE_CHANGED", "TEAM_MEMBER", "TEAM_ADMIN"));
        });

        Map<String, Object> org = jdbc.sql("SELECT * FROM org_activity_log").query().singleRow();
        Map<String, Object> team = jdbc.sql("SELECT * FROM team_activity_log").query().singleRow();

        assertThat(value(org, "org_id")).isEqualTo(ORG.toString());
        assertThat(value(org, "actor_id")).isEqualTo(ACTOR.toString());
        assertThat(value(org, "action")).isEqualTo("ORG_SETTINGS_CHANGED");
        assertThat(value(org, "target_type")).isEqualTo("MEMBERSHIP");
        assertThat(value(org, "target_id")).isEqualTo(TARGET.toString());
        assertThat(value(org, "before_value")).isEqualTo("Asia/Seoul");
        assertThat(value(org, "after_value")).isEqualTo("UTC");
        assertThat(value(org, "occurred_at")).isNotNull();
        assertThat(value(team, "team_id")).isEqualTo(TEAM.toString());
        assertThat(value(team, "actor_id")).isNull();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 기록 호출 거부")
    void rejectsRecordOutsideTransaction() {
        ActivityEntry entry = entry(ACTOR, "TEAM_CREATED", null, null);

        assertThatThrownBy(() -> activityLogs.recordOrg(ORG, entry))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("org_activity_log")).isZero();
    }

    @Test
    @DisplayName("기록 저장 실패 시 같은 트랜잭션의 원래 변경도 롤백")
    void failedRecordRollsBackOriginalChange() {
        String sub = "audit-test-" + UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            insertAccount(sub);
            activityLogs.recordTeam(TEAM, entry(ACTOR, "TEAM_CREATED", null, null));
            activityLogs.recordOrg(null, entry(ACTOR, "TEAM_CREATED", null, null));
        })).isInstanceOf(NullPointerException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM account WHERE google_sub = :sub")
                .param("sub", sub).query(Integer.class).single()).isZero();
        assertThat(count("team_activity_log")).isZero();
        assertThat(count("org_activity_log")).isZero();
    }

    @Test
    @DisplayName("TEN-LOG-03: 값 열의 이메일·URL·토큰·제어 문자·길이 초과 거부")
    void rejectsSensitiveValues() {
        List<String> rejected = List.of(
                "dana@example.com",
                "https://chat.example.com/v1/spaces/x/messages?key=y",
                "Q2hhdFdlYmhvb2tLZXk0NTY3ODkwYWJjZGVm",
                "줄\n바꿈",
                "a".repeat(ActivityEntry.MAX_VALUE_LENGTH + 1));

        for (String value : rejected) {
            assertThatThrownBy(() -> entry(ACTOR, "INVITATION_CREATED", null, value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .message().doesNotContain(value);
            assertThatThrownBy(() -> entry(ACTOR, "INVITATION_CREATED", value, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("행동·대상 종류는 대문자 상수만, 대상 ID는 필수")
    void rejectsMalformedCodes() {
        assertThatThrownBy(() -> new ActivityEntry(ACTOR, "team_created", "TEAM", TARGET, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActivityEntry(ACTOR, "TEAM_CREATED", "TEAM'; --", TARGET, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActivityEntry(ACTOR, "A".repeat(41), "TEAM", TARGET, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActivityEntry(ACTOR, "TEAM_CREATED", "TEAM", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("UUID·대문자 상수·평범한 글은 값으로 허용")
    void acceptsOrdinaryValues() {
        transactions.executeWithoutResult(status -> activityLogs.recordTeam(TEAM,
                entry(ACTOR, "TASK_ASSIGNEE_CHANGED", ACTOR.toString(), "주간 회의 준비")));

        assertThat(count("team_activity_log")).isOne();
    }

    private static ActivityEntry entry(UUID actor, String action, String before, String after) {
        return new ActivityEntry(actor, action, "MEMBERSHIP", TARGET, before, after);
    }

    private void insertAccount(String sub) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 9, 0, 0);
        jdbc.sql("""
                        INSERT INTO account (id, google_sub, display_name_custom, state, created_at, updated_at)
                        VALUES (:id, :sub, :custom, 'ACTIVE', :now, :now)
                        """)
                .param("id", Uuids.v7().toString())
                .param("sub", sub)
                .param("custom", false)
                .param("now", now)
                .update();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private static Object value(Map<String, Object> row, String column) {
        String key = row.keySet().stream()
                .filter(name -> name.equalsIgnoreCase(column))
                .findFirst()
                .orElseThrow();
        Object value = row.get(key);
        return value == null ? null : value.toString().trim();
    }

}
