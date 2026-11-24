package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import reberu.core.tenancy.service.MemberService;
import reberu.core.tenancy.service.TeamMemberService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeaveTest {

    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "team_membership",
            "team", "job_type", "org_membership", "organization", "api_idempotency_key", "auth_session",
            "account_consent", "account");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    MemberService members;

    @Autowired
    TeamMemberService teamMembers;

    private UUID admin;
    private UUID member;
    private UUID outsider;
    private String orgId;
    private String firstTeamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = newAccount("admin");
        member = newAccount("member");
        outsider = newAccount("outsider");
        JsonNode created = json(createOrg(admin, "새벽 서재"), 201);
        orgId = created.get("orgId").asString();
        firstTeamId = created.get("teamId").asString();
        insertOrgMembership(member, "ACTIVE", false);
        insertTeamMembership(firstTeamId, member, "TEAM_MEMBER", "ACTIVE");
    }

    @Test
    @DisplayName("TEN-LEAVE-02: 조직 나가기는 조직·팀 멤버십 LEFT(사유 SELF), 팀 요청은 취소, 관리자 여부 해제, 세션 유지")
    void leavesOrg() throws Exception {
        String suspendedTeam = createTeam("둘째 팀");
        insertTeamMembership(suspendedTeam, member, "TEAM_MEMBER", "SUSPENDED");
        String requestedTeam = createTeam("셋째 팀");
        insertTeamMembership(requestedTeam, member, "TEAM_MEMBER", "REQUESTED");
        jdbc.sql("UPDATE org_membership SET org_admin = :admin WHERE account_id = :account")
                .param("admin", true).param("account", member.toString()).update();
        insertSession(member);

        assertThat(perform(post(leaveOrg(orgId)), member).getStatus()).isEqualTo(204);

        assertThat(orgMembership(member)).isEqualTo("LEFT:SELF:false");
        assertThat(jdbc.sql("SELECT CONCAT(state, ':', left_reason) FROM team_membership WHERE account_id = :account")
                .param("account", member.toString()).query(String.class).list())
                .containsOnly("LEFT:SELF").hasSize(3);
        assertThat(orgLog("ORG_MEMBER_LEFT")).containsExactly(member + " " + member + " ACTIVE LEFT");
        assertThat(teamLog("TEAM_MEMBER_LEFT")).containsExactlyInAnyOrder(
                firstTeamId + " " + member + " ACTIVE LEFT", suspendedTeam + " " + member + " SUSPENDED LEFT");
        assertThat(teamLog("TEAM_MEMBER_REQUEST_CANCELLED"))
                .containsExactly(requestedTeam + " " + member + " REQUESTED LEFT");
        assertThat(sessionCount(member)).isOne();
        assertThat(perform(get("/api/orgs/" + orgId), member).getStatus()).isEqualTo(404);
        assertThat(perform(post(leaveOrg(orgId)), member).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("TEN-LEAVE-04: 팀 나가기는 그 팀 멤버십만 LEFT(사유 SELF), 활동 기록, 다시 하면 404")
    void leavesTeam() throws Exception {
        assertThat(perform(post(leaveTeam(firstTeamId)), member).getStatus()).isEqualTo(204);

        assertThat(jdbc.sql("""
                        SELECT CONCAT(state, ':', left_reason) FROM team_membership
                        WHERE team_id = :team AND account_id = :account
                        """)
                .param("team", firstTeamId).param("account", member.toString()).query(String.class).single())
                .isEqualTo("LEFT:SELF");
        assertThat(orgMembership(member)).isEqualTo("ACTIVE:-:false");
        assertThat(teamLog("TEAM_MEMBER_LEFT")).containsExactly(firstTeamId + " " + member + " ACTIVE LEFT");
        assertThat(perform(get("/api/teams/" + firstTeamId), member).getStatus()).isEqualTo(404);
        assertThat(perform(post(leaveTeam(firstTeamId)), member).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("TEN-LEAVE-03·04: 마지막 조직 관리자·마지막 팀 관리자는 409 LAST_ADMIN_REQUIRED, 조직에서 정지된 팀 관리자는 세지 않음")
    void keepsLastAdmins() throws Exception {
        setTeamRole(member, "TEAM_ADMIN");
        assertThat(code(perform(post(leaveOrg(orgId)), admin), 409)).isEqualTo("LAST_ADMIN_REQUIRED");

        setTeamRole(member, "TEAM_MEMBER");
        jdbc.sql("UPDATE org_membership SET org_admin = :admin WHERE account_id = :account")
                .param("admin", true).param("account", member.toString()).update();
        assertThat(code(perform(post(leaveOrg(orgId)), admin), 409)).isEqualTo("LAST_ADMIN_REQUIRED");
        assertThat(code(perform(post(leaveTeam(firstTeamId)), admin), 409)).isEqualTo("LAST_ADMIN_REQUIRED");

        UUID suspended = newAccount("suspended");
        insertOrgMembership(suspended, "SUSPENDED", false);
        insertTeamMembership(firstTeamId, suspended, "TEAM_ADMIN", "ACTIVE");
        assertThat(code(perform(post(leaveTeam(firstTeamId)), admin), 409)).isEqualTo("LAST_ADMIN_REQUIRED");

        setTeamRole(member, "TEAM_ADMIN");
        assertThat(perform(post(leaveOrg(orgId)), admin).getStatus()).isEqualTo(204);
        assertThat(orgMembership(admin)).isEqualTo("LEFT:SELF:false");
    }

    @Test
    @DisplayName("TEN-LEAVE-01: 내 멤버십이 없으면 404, 정지됐으면 403, 보관된 조직·팀은 409 READ_ONLY(멤버가 아니면 404)")
    void rejectsUnavailableLeaves() throws Exception {
        String unknown = UUID.randomUUID().toString();
        assertThat(perform(post(leaveOrg(orgId)), outsider).getStatus()).isEqualTo(404);
        assertThat(perform(post(leaveTeam(firstTeamId)), outsider).getStatus()).isEqualTo(404);
        assertThat(perform(post(leaveOrg(unknown)), member).getStatus()).isEqualTo(404);
        assertThat(perform(post(leaveTeam(unknown)), member).getStatus()).isEqualTo(404);
        String otherTeam = createTeam("둘째 팀");
        assertThat(perform(post(leaveTeam(otherTeam)), member).getStatus()).isEqualTo(404);

        jdbc.sql("UPDATE team_membership SET state = 'SUSPENDED' WHERE team_id = :team AND account_id = :account")
                .param("team", firstTeamId).param("account", member.toString()).update();
        assertThat(code(perform(post(leaveTeam(firstTeamId)), member), 403)).isEqualTo("FORBIDDEN_ACTION");
        jdbc.sql("UPDATE org_membership SET state = 'SUSPENDED' WHERE account_id = :account")
                .param("account", member.toString()).update();
        assertThat(code(perform(post(leaveOrg(orgId)), member), 403)).isEqualTo("FORBIDDEN_ACTION");
        jdbc.sql("UPDATE org_membership SET state = 'ACTIVE' WHERE account_id = :account")
                .param("account", member.toString()).update();
        jdbc.sql("UPDATE team_membership SET state = 'ACTIVE' WHERE account_id = :account")
                .param("account", member.toString()).update();

        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :team").param("team", firstTeamId).update();
        assertThat(code(perform(post(leaveTeam(firstTeamId)), member), 409)).isEqualTo("READ_ONLY");
        jdbc.sql("UPDATE organization SET state = 'ARCHIVED' WHERE id = :org").param("org", orgId).update();
        assertThat(code(perform(post(leaveOrg(orgId)), member), 409)).isEqualTo("READ_ONLY");
        assertThat(perform(post(leaveOrg(orgId)), outsider).getStatus()).isEqualTo(404);
        assertThat(orgMembership(member)).isEqualTo("ACTIVE:-:false");
    }

    @Test
    @DisplayName("T-AUTHZ-LASTADMIN: 조직 관리자 둘이 동시에 조직을 나가도, 팀 관리자 둘이 동시에 팀을 나가도 관리자가 남음")
    void keepsAdminsUnderConcurrency() throws Exception {
        jdbc.sql("UPDATE org_membership SET org_admin = :admin WHERE account_id = :account")
                .param("admin", true).param("account", member.toString()).update();
        String team = createTeam("둘째 팀");
        insertTeamMembership(team, member, "TEAM_ADMIN", "ACTIVE");

        assertThat(race(() -> teamMembers.leave(UUID.fromString(team), admin), leaveTeam(team))).isEqualTo(409);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM team_membership
                        WHERE team_id = :team AND role = 'TEAM_ADMIN' AND state = 'ACTIVE'
                        """)
                .param("team", team).query(Integer.class).single()).isOne();

        jdbc.sql("DELETE FROM team_membership WHERE team_id = :team").param("team", team).update();
        UUID helper = newAccount("helper");
        insertOrgMembership(helper, "ACTIVE", false);
        insertTeamMembership(firstTeamId, helper, "TEAM_ADMIN", "ACTIVE");
        assertThat(race(() -> members.leave(UUID.fromString(orgId), admin), leaveOrg(orgId))).isEqualTo(409);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM org_membership
                        WHERE org_id = :org AND org_admin = :admin AND state = 'ACTIVE'
                        """)
                .param("org", orgId).param("admin", true).query(Integer.class).single()).isOne();
    }

    private int race(Runnable adminLeave, String memberPath) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactions.executeWithoutResult(status -> {
            adminLeave.run();
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try {
                return perform(post(memberPath), member).getStatus();
            }
            catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500);
        assertThat(second.isDone()).as("잠금 대기").isFalse();
        release.countDown();
        holder.join();
        return second.get(30, TimeUnit.SECONDS);
    }

    private void setTeamRole(UUID account, String role) {
        jdbc.sql("UPDATE team_membership SET role = :role WHERE team_id = :team AND account_id = :account")
                .param("role", role).param("team", firstTeamId).param("account", account.toString()).update();
    }

    private static String leaveOrg(String org) {
        return "/api/me/orgs/" + org + "/leave";
    }

    private static String leaveTeam(String team) {
        return "/api/me/teams/" + team + "/leave";
    }

    private String createTeam(String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", orgId).query(String.class).single());
        body.put("firstAdminAccountId", admin.toString());
        MockHttpServletResponse response = perform(post("/api/orgs/" + orgId + "/teams")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), admin);
        return json(response, 201).get("teamId").asString();
    }

    private MockHttpServletResponse createOrg(UUID actor, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("timeZone", "Asia/Seoul");
        body.put("firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"));
        return perform(post("/api/orgs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), actor);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private void insertOrgMembership(UUID account, String state, boolean orgAdmin) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            created_at, updated_at, version, admission_review)
                        VALUES (:id, :org, :account, 'MEMBER', :admin, :state, 'INVITATION', :now, :now, 0, FALSE)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", account.toString())
                .param("admin", orgAdmin)
                .param("state", state)
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String teamId, UUID account, String role, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, created_at,
                            updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'DIRECT', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", teamId)
                .param("account", account.toString())
                .param("role", role)
                .param("state", state)
                .param("now", now)
                .update();
    }

    private void insertSession(UUID account) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO auth_session (session_id_hash, account_id, authenticated_at, created_at,
                            last_used_at, expires_at)
                        VALUES (:hash, :account, :now, :now, :now, :expires)
                        """)
                .param("hash", (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""))
                .param("account", account.toString())
                .param("now", now)
                .param("expires", now.plusHours(1))
                .update();
    }

    private int sessionCount(UUID account) {
        return jdbc.sql("SELECT COUNT(*) FROM auth_session WHERE account_id = :account")
                .param("account", account.toString()).query(Integer.class).single();
    }

    private String orgMembership(UUID account) {
        return jdbc.sql("""
                        SELECT state, left_reason, org_admin FROM org_membership
                        WHERE org_id = :org AND account_id = :account
                        """)
                .param("org", orgId).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + ":"
                        + (rs.getString("left_reason") == null ? "-" : rs.getString("left_reason")) + ":"
                        + rs.getBoolean("org_admin"))
                .single();
    }

    private List<String> orgLog(String action) {
        return jdbc.sql("""
                        SELECT actor_id, target_id, before_value, after_value FROM org_activity_log
                        WHERE org_id = :org AND action = :action ORDER BY occurred_at
                        """)
                .param("org", orgId).param("action", action)
                .query((rs, rowNum) -> rs.getString("actor_id") + " " + rs.getString("target_id") + " "
                        + rs.getString("before_value") + " " + rs.getString("after_value"))
                .list();
    }

    private List<String> teamLog(String action) {
        return jdbc.sql("""
                        SELECT team_id, target_id, before_value, after_value FROM team_activity_log
                        WHERE action = :action AND actor_id = target_id ORDER BY occurred_at
                        """)
                .param("action", action)
                .query((rs, rowNum) -> rs.getString("team_id") + " " + rs.getString("target_id") + " "
                        + rs.getString("before_value") + " " + rs.getString("after_value"))
                .list();
    }

    private String code(MockHttpServletResponse response, int status) throws Exception {
        return json(response, status).get("code").asString();
    }

    private JsonNode json(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return jsonMapper.readTree(response.getContentAsString());
    }

    private UUID newAccount(String name) {
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(),
                name + "@example.com", name, null, null), "v1", "v1", Instant.now());
    }

}
