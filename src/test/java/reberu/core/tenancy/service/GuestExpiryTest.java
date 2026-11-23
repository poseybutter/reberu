package reberu.core.tenancy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.audit.ActivityLogs;
import reberu.core.auth.security.AccountAuthentication;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class GuestExpiryTest {

    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "invite_link",
            "invitation", "team_custom_field", "team_task_label", "team_membership", "team", "job_type",
            "org_membership", "organization", "api_idempotency_key", "account_consent", "account");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    GuestExpiry expiry;

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    OrgMembershipRepository orgMemberships;

    @Autowired
    TeamMembershipRepository teamMemberships;

    @Autowired
    ActivityLogs activityLogs;

    @Autowired
    TransactionTemplate transactions;

    private UUID orgAdmin;
    private String orgId;
    private String forest;
    private String sea;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        orgAdmin = newAccount("orgadmin@example.com");
        orgId = createOrg(orgAdmin, "새벽 서재");
        forest = teamOf(orgId);
        sea = createTeam(orgId, orgAdmin, "바다 팀");
    }

    @Test
    @DisplayName("TEN-GUEST-11: 지난 팀만 EXPIRED, 끝나지 않은 팀이 없거나 조직 만료 시각이 지나면 조직과 정지된 팀도 EXPIRED, 정지된 손님 조직은 그대로, 기록은 행동한 사람 없이")
    void expiresDueGuests(CapturedOutput output) throws Exception {
        Instant now = Instant.now();
        Instant past = now.minusSeconds(60);
        Instant later = now.plus(Duration.ofDays(10));

        UUID partial = newAccount("partial@example.com");
        insertOrgMembership(orgId, partial, "GUEST", "ACTIVE", later);
        insertTeamMembership(forest, partial, "TEAM_MEMBER", "ACTIVE", past);
        insertTeamMembership(sea, partial, "VIEWER", "ACTIVE", later);

        UUID single = newAccount("single@example.com");
        insertOrgMembership(orgId, single, "GUEST", "ACTIVE", later);
        insertTeamMembership(forest, single, "TEAM_MEMBER", "ACTIVE", past);

        UUID overdue = newAccount("overdue@example.com");
        insertOrgMembership(orgId, overdue, "GUEST", "ACTIVE", past);
        insertTeamMembership(sea, overdue, "VIEWER", "SUSPENDED", later);

        String otherOrg = createOrg(newAccount("other@example.com"), "다른 서재");
        String otherTeam = teamOf(otherOrg);
        UUID removed = newAccount("removed@example.com");
        insertOrgMembership(otherOrg, removed, "GUEST", "ACTIVE", later);
        insertTeamMembership(otherTeam, removed, "TEAM_MEMBER", "LEFT", later);

        UUID suspended = newAccount("suspended@example.com");
        insertOrgMembership(orgId, suspended, "GUEST", "SUSPENDED", past);
        insertTeamMembership(sea, suspended, "VIEWER", "SUSPENDED", later);

        UUID member = newAccount("member@example.com");
        insertOrgMembership(orgId, member, "MEMBER", "ACTIVE", null);
        insertTeamMembership(forest, member, "TEAM_MEMBER", "ACTIVE", null);

        UUID fresh = newAccount("fresh@example.com");
        insertOrgMembership(orgId, fresh, "GUEST", "ACTIVE", later);
        insertTeamMembership(forest, fresh, "TEAM_MEMBER", "ACTIVE", later);

        assertThat(expiry.expireDue()).isEqualTo(6);

        assertThat(orgState(partial)).isEqualTo("ACTIVE");
        assertThat(teamState(forest, partial)).isEqualTo("EXPIRED");
        assertThat(teamState(sea, partial)).isEqualTo("ACTIVE");
        assertThat(orgState(single)).isEqualTo("EXPIRED");
        assertThat(teamState(forest, single)).isEqualTo("EXPIRED");
        assertThat(orgState(overdue)).isEqualTo("EXPIRED");
        assertThat(teamState(sea, overdue)).isEqualTo("EXPIRED");
        assertThat(orgState(removed, otherOrg)).isEqualTo("EXPIRED");
        assertThat(teamState(otherTeam, removed)).isEqualTo("LEFT");
        assertThat(orgState(suspended)).isEqualTo("SUSPENDED");
        assertThat(teamState(sea, suspended)).isEqualTo("SUSPENDED");
        assertThat(orgState(member)).isEqualTo("ACTIVE");
        assertThat(teamState(forest, member)).isEqualTo("ACTIVE");
        assertThat(orgState(fresh)).isEqualTo("ACTIVE");
        assertThat(teamState(forest, fresh)).isEqualTo("ACTIVE");

        assertThat(logs("team_activity_log")).containsExactlyInAnyOrder(
                "null " + partial + " ACTIVE EXPIRED",
                "null " + single + " ACTIVE EXPIRED",
                "null " + overdue + " SUSPENDED EXPIRED");
        assertThat(logs("org_activity_log")).containsExactlyInAnyOrder(
                "null " + single + " ACTIVE EXPIRED",
                "null " + overdue + " ACTIVE EXPIRED",
                "null " + removed + " ACTIVE EXPIRED");
        assertThat(output).contains("손님 만료 처리: 멤버십 6건");

        assertThat(expiry.expireDue()).isZero();
    }

    @Test
    @DisplayName("TEN-GUEST-11: 팀 단위로 정지된 손님 팀은 만료 시각이 지나도 SUSPENDED 유지, 정지를 풀면 다음 작업에서 EXPIRED")
    void keepsTeamSuspendedGuestUntilUnsuspended() throws Exception {
        Instant now = Instant.now();
        UUID guest = newAccount("paused@example.com");
        insertOrgMembership(orgId, guest, "GUEST", "ACTIVE", now.plus(Duration.ofDays(10)));
        insertTeamMembership(forest, guest, "TEAM_MEMBER", "SUSPENDED", now.minusSeconds(60));
        insertTeamMembership(sea, guest, "VIEWER", "ACTIVE", now.plus(Duration.ofDays(10)));
        UUID due = newAccount("due@example.com");
        insertOrgMembership(orgId, due, "GUEST", "ACTIVE", now.plus(Duration.ofDays(10)));
        insertTeamMembership(forest, due, "TEAM_MEMBER", "ACTIVE", now.minusSeconds(60));
        insertTeamMembership(sea, due, "VIEWER", "ACTIVE", now.plus(Duration.ofDays(10)));

        assertThat(expiry.expireDue()).isEqualTo(1);

        assertThat(teamState(forest, due)).isEqualTo("EXPIRED");
        assertThat(orgState(guest)).isEqualTo("ACTIVE");
        assertThat(teamState(forest, guest)).isEqualTo("SUSPENDED");
        assertThat(teamState(sea, guest)).isEqualTo("ACTIVE");
        assertThat(logs("team_activity_log")).containsExactly("null " + due + " ACTIVE EXPIRED");

        MockHttpServletResponse unsuspended = perform(
                post("/api/teams/" + forest + "/members/" + guest + "/unsuspend"), orgAdmin, Map.of());
        assertThat(unsuspended.getStatus()).as(unsuspended.getContentAsString()).isEqualTo(204);

        assertThat(expiry.expireDue()).isEqualTo(1);

        assertThat(orgState(guest)).isEqualTo("ACTIVE");
        assertThat(teamState(forest, guest)).isEqualTo("EXPIRED");
        assertThat(teamState(sea, guest)).isEqualTo("ACTIVE");
        assertThat(logs("team_activity_log")).containsExactlyInAnyOrder("null " + due + " ACTIVE EXPIRED",
                "null " + guest + " ACTIVE EXPIRED");
    }

    @Test
    @DisplayName("TEN-ISO-07: 한 조직의 만료 처리가 실패해도 다른 조직은 처리하고, 실패한 조직은 하나도 바뀌지 않음")
    void failureStaysInsideOneOrg(CapturedOutput output) throws Exception {
        Instant past = Instant.now().minusSeconds(60);
        UUID broken = newAccount("broken@example.com");
        insertOrgMembership(orgId, broken, "GUEST", "ACTIVE", past);
        insertTeamMembership(forest, broken, "TEAM_MEMBER", "ACTIVE", past);

        UUID otherAdmin = newAccount("other@example.com");
        String otherOrg = createOrg(otherAdmin, "다른 서재");
        UUID guest = newAccount("guest@example.com");
        insertOrgMembership(otherOrg, guest, "GUEST", "ACTIVE", past);
        insertTeamMembership(teamOf(otherOrg), guest, "TEAM_MEMBER", "ACTIVE", past);

        UUID failingOrg = UUID.fromString(orgId);
        ActivityLogs failing = (ActivityLogs) Proxy.newProxyInstance(ActivityLogs.class.getClassLoader(),
                new Class<?>[] {ActivityLogs.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("recordOrg") && failingOrg.equals(arguments[0])) {
                        throw new IllegalStateException("기록 실패");
                    }
                    try {
                        return method.invoke(activityLogs, arguments);
                    }
                    catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        GuestExpiry job = new GuestExpiry(organizations, orgMemberships, teamMemberships, failing, transactions,
                jdbc, Clock.systemUTC());

        assertThat(job.expireDue()).isEqualTo(2);

        assertThat(orgState(guest, otherOrg)).isEqualTo("EXPIRED");
        assertThat(orgState(broken)).isEqualTo("ACTIVE");
        assertThat(teamState(forest, broken)).isEqualTo("ACTIVE");
        assertThat(logs("team_activity_log")).containsExactly("null " + guest + " ACTIVE EXPIRED");
        assertThat(output).contains("손님 만료 처리 실패: 조직 " + orgId);
    }

    private String orgState(UUID account) {
        return orgState(account, orgId);
    }

    private String orgState(UUID account, String org) {
        return jdbc.sql("SELECT state FROM org_membership WHERE org_id = :org AND account_id = :account")
                .param("org", org).param("account", account.toString()).query(String.class).single();
    }

    private String teamState(String team, UUID account) {
        return jdbc.sql("SELECT state FROM team_membership WHERE team_id = :team AND account_id = :account")
                .param("team", team).param("account", account.toString()).query(String.class).single();
    }

    private List<String> logs(String table) {
        return jdbc.sql("SELECT actor_id, target_id, before_value, after_value FROM " + table
                        + " WHERE action = 'GUEST_EXPIRED'")
                .query((rs, rowNum) -> rs.getString("actor_id") + " " + rs.getString("target_id") + " "
                        + rs.getString("before_value") + " " + rs.getString("after_value"))
                .list();
    }

    private void insertOrgMembership(String org, UUID account, String kind, String state, Instant expiresAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version, admission_review)
                        VALUES (:id, :org, :account, :kind, FALSE, :state, 'INVITATION', :expiresAt, :now, :now, 0,
                            FALSE)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", org)
                .param("account", account.toString())
                .param("kind", kind)
                .param("state", state)
                .param("expiresAt", timestamp(expiresAt))
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String team, UUID account, String role, String state, Instant expiresAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, expires_at,
                            created_at, updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'INVITATION', :expiresAt, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", team)
                .param("account", account.toString())
                .param("role", role)
                .param("state", state)
                .param("expiresAt", timestamp(expiresAt))
                .param("now", now)
                .update();
    }

    private String teamOf(String org) {
        return jdbc.sql("SELECT id FROM team WHERE org_id = :org AND name = '첫 팀'").param("org", org)
                .query(String.class).single();
    }

    private String createTeam(String org, UUID actor, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", org).query(String.class).single());
        body.put("firstAdminAccountId", actor.toString());
        return created(perform(post("/api/orgs/" + org + "/teams"), actor, body)).get("teamId");
    }

    private String createOrg(UUID actor, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("timeZone", "Asia/Seoul");
        body.put("firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"));
        return created(perform(post("/api/orgs"), actor, body)).get("orgId");
    }

    private MockHttpServletResponse perform(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, UUID actor,
            Map<String, Object> body) throws Exception {
        return mockMvc.perform(request.header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body))
                .with(authentication(new AccountAuthentication(actor, Instant.now()))).with(csrfToken()))
                .andReturn().getResponse();
    }

    private Map<String, String> created(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        Map<String, String> values = new LinkedHashMap<>();
        jsonMapper.readTree(response.getContentAsString()).properties()
                .forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
        return values;
    }

    private UUID newAccount(String email) {
        String name = email.substring(0, email.indexOf('@')).toLowerCase();
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(), email, name,
                null, null), "v1", "v1", Instant.now());
    }

    private static LocalDateTime timestamp(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

}
