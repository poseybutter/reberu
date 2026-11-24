package reberu.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import fakemodule.FakeEvents;
import fakemodule.FakeModules;
import fakemodule.org.FakeOrgModule;
import fakemodule.team.FakeTeamModule;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;

@SpringBootTest
@Import({TestcontainersConfiguration.class, FakeModules.class})
class ModuleEventDeliveryTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    CompletedEventPublications completed;

    @Autowired
    JdbcClient jdbc;

    private final UUID orgId = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM team_module_state WHERE team_id = :team").param("team", teamId.toString()).update();
        jdbc.sql("DELETE FROM org_module_state WHERE org_id = :org").param("org", orgId.toString()).update();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team (id, org_id, name, name_key, visibility, join_mode, state, job_type_id,
                            report_week_start, report_notice_titles, created_at, updated_at, version)
                        VALUES (:team, :org, '이벤트 팀', :key, 'PRIVATE', 'APPROVAL', 'ACTIVE', NULL, 1, '[]', :now,
                            :now, 0)
                        """)
                .param("team", teamId.toString())
                .param("org", orgId.toString())
                .param("key", teamId.toString().substring(0, 30))
                .param("now", now)
                .update();
    }

    @Test
    @DisplayName("ARCH-EVT-05·SEC-MOD-04: 모듈이 꺼진 팀의 이벤트는 그 모듈 구독자에게 가지 않고 완료로 기록, 켜면 전달, 모듈 밖 구독자는 늘 받음")
    void teamEventsFollowModuleSwitch() {
        UUID off = UUID.randomUUID();
        publish(new FakeEvents.TeamPinged(orgId, teamId, off));
        await().atMost(WAIT).until(() -> completedDeliveries(off) == 2);
        assertThat(FakeEvents.wasReceived(FakeTeamModule.Listener.NAME, off)).isFalse();
        assertThat(FakeEvents.wasReceived(FakeEvents.UnownedListener.NAME, off)).isTrue();

        enableTeam(FakeTeamModule.KEY);
        UUID on = UUID.randomUUID();
        publish(new FakeEvents.TeamPinged(orgId, teamId, on));
        await().atMost(WAIT).until(() -> FakeEvents.wasReceived(FakeTeamModule.Listener.NAME, on));
        await().atMost(WAIT).until(() -> completedDeliveries(on) == 2);
    }

    @Test
    @DisplayName("ARCH-EVT-05: 조직 범위 모듈 구독자는 조직이 켰을 때만 받고, 꺼져 있으면 완료로만 기록")
    void orgEventsFollowModuleSwitch() {
        UUID off = UUID.randomUUID();
        publish(new FakeEvents.OrgPinged(orgId, off));
        await().atMost(WAIT).until(() -> completedDeliveries(off) == 1);
        assertThat(FakeEvents.wasReceived(FakeOrgModule.Listener.NAME, off)).isFalse();

        jdbc.sql("""
                        INSERT INTO org_module_state (id, org_id, module_key, allowed, enabled, changed_by, changed_at)
                        VALUES (:id, :org, :key, :allowed, :enabled, NULL, :now)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId.toString())
                .param("key", FakeOrgModule.KEY)
                .param("allowed", false)
                .param("enabled", true)
                .param("now", LocalDateTime.now(ZoneOffset.UTC))
                .update();
        UUID on = UUID.randomUUID();
        publish(new FakeEvents.OrgPinged(orgId, on));
        await().atMost(WAIT).until(() -> FakeEvents.wasReceived(FakeOrgModule.Listener.NAME, on));
    }

    private void publish(Object event) {
        transactions.executeWithoutResult(status -> events.publishEvent(event));
    }

    private void enableTeam(String key) {
        jdbc.sql("""
                        INSERT INTO team_module_state (id, team_id, module_key, enabled, changed_by, changed_at)
                        VALUES (:id, :team, :key, :enabled, NULL, :now)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", teamId.toString())
                .param("key", key)
                .param("enabled", true)
                .param("now", LocalDateTime.now(ZoneOffset.UTC))
                .update();
    }

    private long completedDeliveries(UUID id) {
        return completed.findAll().stream().filter(publication -> about(publication.getEvent(), id)).count();
    }

    private static boolean about(Object event, UUID id) {
        return (event instanceof FakeEvents.TeamPinged team && team.id().equals(id))
                || (event instanceof FakeEvents.OrgPinged org && org.id().equals(id));
    }

}
