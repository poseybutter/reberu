package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static reberu.TestCsrf.csrfToken;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TeamMemberTest {

    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "team_custom_field",
            "team_task_label", "team_membership", "team", "job_type", "org_membership", "organization",
            "api_idempotency_key", "account_consent", "account");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    private UUID orgAdmin;
    private UUID teamAdmin;
    private UUID member;
    private UUID viewer;
    private UUID guest;
    private UUID candidate;
    private UUID outsider;
    private String orgId;
    private String teamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        orgAdmin = newAccount("orgadmin");
        teamAdmin = newAccount("teamadmin");
        member = newAccount("member");
        viewer = newAccount("viewer");
        guest = newAccount("guest");
        candidate = newAccount("candidate");
        outsider = newAccount("outsider");
        orgId = json(createOrg(orgAdmin, "새벽 서재"), 201).get("orgId").asString();
        for (UUID account : List.of(teamAdmin, member, viewer, candidate)) {
            insertOrgMembership(account, "MEMBER", "ACTIVE");
        }
        insertOrgMembership(guest, "GUEST", "ACTIVE");
        teamId = createTeam("개발 팀", teamAdmin);
        insertTeamMembership(teamId, member, "TEAM_MEMBER", "ACTIVE");
        insertTeamMembership(teamId, viewer, "VIEWER", "ACTIVE");
        insertTeamMembership(teamId, guest, "TEAM_MEMBER", "ACTIVE");
    }

    @Test
    @DisplayName("TEN-TMEM-01: 관리자는 이메일·정지된 사람까지, 팀원·읽기 전용은 이메일 없이 ACTIVE만, 쿼리 state는 관리자만")
    void listsTeamMembers() throws Exception {
        assertThat(act(member, "/suspend")).isEqualTo(204);

        JsonNode forAdmin = json(perform(get(path("/members")), teamAdmin), 200);
        JsonNode forMember = json(perform(get(path("/members?state=SUSPENDED")), viewer), 200);

        assertThat(accountIds(forAdmin)).containsExactly(teamAdmin.toString(), member.toString(), viewer.toString(),
                guest.toString());
        assertThat(forAdmin.get("items").get(0).get("email").asString()).isEqualTo("teamadmin@example.com");
        assertThat(forAdmin.get("items").get(3).get("kind").asString()).isEqualTo("GUEST");
        assertThat(accountIds(forMember)).containsExactly(teamAdmin.toString(), viewer.toString(), guest.toString());
        forMember.get("items").forEach(item -> assertThat(item.get("email").isNull()).isTrue());
        assertThat(accountIds(json(perform(get(path("/members?state=SUSPENDED")), orgAdmin), 200)))
                .containsExactly(member.toString());
        assertThat(perform(get(path("/members?state=REQUESTED")), teamAdmin).getStatus()).isEqualTo(400);
        JsonNode first = json(perform(get(path("/members?limit=3")), teamAdmin), 200);
        JsonNode second = json(perform(get(path("/members?limit=3&cursor=" + first.get("nextCursor").asString())),
                teamAdmin), 200);
        assertThat(accountIds(second)).containsExactly(guest.toString());
        assertThat(perform(get(path("/members")), outsider).getStatus()).isEqualTo(404);
        assertThat(perform(get(path("/members")), member).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("TEN-TMEM-02: 바로 추가는 조직의 ACTIVE 구성원만, TEAM_MEMBER·VIEWER만, 나간 사람은 같은 행을 다시 씀")
    void addsTeamMembers() throws Exception {
        assertThat(add(teamAdmin, candidate, "VIEWER").getStatus()).isEqualTo(201);
        assertThat(code(add(teamAdmin, candidate, "VIEWER"), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(fields(add(teamAdmin, outsider, "TEAM_MEMBER"))).containsExactly("accountId:NOT_ALLOWED_VALUE");
        UUID newGuest = newAccount("newguest");
        insertOrgMembership(newGuest, "GUEST", "ACTIVE");
        assertThat(fields(add(teamAdmin, newGuest, "TEAM_MEMBER"))).containsExactly("accountId:NOT_ALLOWED_VALUE");
        UUID another = newAccount("another");
        insertOrgMembership(another, "MEMBER", "ACTIVE");
        assertThat(fields(add(teamAdmin, another, "TEAM_ADMIN"))).containsExactly("role:NOT_ALLOWED_VALUE");
        assertThat(add(member, another, "TEAM_MEMBER").getStatus()).isEqualTo(403);

        assertThat(act(candidate, "/remove")).isEqualTo(204);
        assertThat(add(orgAdmin, candidate, "TEAM_MEMBER").getStatus()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT CONCAT(state, ':', role) FROM team_membership WHERE team_id = :team"
                        + " AND account_id = :account")
                .param("team", teamId).param("account", candidate.toString()).query(String.class).list())
                .containsExactly("ACTIVE:TEAM_MEMBER");
        assertThat(teamLog("TEAM_MEMBER_ADDED")).containsExactly(teamAdmin + " " + candidate + " - VIEWER",
                orgAdmin + " " + candidate + " LEFT TEAM_MEMBER");
        assertThat(perform(get("/api/teams/" + teamId), candidate).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("TEN-TMEM-03: 역할 바꾸기는 version 필수, 같은 역할 409, 손님 TEAM_ADMIN 400, 혼자인 관리자 강등 409")
    void changesRoles() throws Exception {
        assertThat(changeRole(teamAdmin, teamAdmin, "TEAM_MEMBER", 0)).isEqualTo(409);
        assertThat(changeRole(teamAdmin, guest, "TEAM_ADMIN", 0)).isEqualTo(400);
        assertThat(changeRole(teamAdmin, member, "TEAM_MEMBER", 0)).isEqualTo(409);
        assertThat(changeRole(teamAdmin, member, "TEAM_ADMIN", 0)).isEqualTo(204);
        assertThat(changeRole(teamAdmin, member, "VIEWER", 0)).isEqualTo(409);
        assertThat(code(perform(put(path("/members/" + member + "/role")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"VIEWER\",\"version\":0}"), teamAdmin), 409)).isEqualTo("VERSION_CONFLICT");
        assertThat(changeRole(teamAdmin, teamAdmin, "TEAM_MEMBER", 0)).isEqualTo(204);
        assertThat(perform(get(path("/settings")), teamAdmin).getStatus()).isEqualTo(403);
        assertThat(perform(get(path("/settings")), member).getStatus()).isEqualTo(200);
        assertThat(teamLog("TEAM_ROLE_CHANGED")).containsExactly(teamAdmin + " " + member + " TEAM_MEMBER TEAM_ADMIN",
                teamAdmin + " " + teamAdmin + " TEAM_ADMIN TEAM_MEMBER");
        assertThat(changeRole(viewer, guest, "VIEWER", 0)).isEqualTo(403);
    }

    @Test
    @DisplayName("정지·해제·내보내기: 반복 409, 정지·내보낸 사람은 그 팀 404, 자기 자신 400, 마지막 팀 관리자 409")
    void suspendsAndRemoves() throws Exception {
        assertThat(act(member, "/suspend")).isEqualTo(204);
        assertThat(act(member, "/suspend")).isEqualTo(409);
        assertThat(perform(get("/api/teams/" + teamId), member).getStatus()).isEqualTo(404);
        assertThat(act(member, "/unsuspend")).isEqualTo(204);
        assertThat(act(member, "/unsuspend")).isEqualTo(409);
        assertThat(perform(get("/api/teams/" + teamId), member).getStatus()).isEqualTo(200);
        assertThat(act(member, "/remove")).isEqualTo(204);
        assertThat(act(member, "/remove")).isEqualTo(404);
        assertThat(perform(get("/api/teams/" + teamId), member).getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT CONCAT(state, ':', left_reason) FROM team_membership WHERE team_id = :team"
                        + " AND account_id = :account")
                .param("team", teamId).param("account", member.toString()).query(String.class).single())
                .isEqualTo("LEFT:REMOVED");

        assertThat(act(teamAdmin, "/suspend")).isEqualTo(400);
        assertThat(act(teamAdmin, "/remove")).isEqualTo(400);
        assertThat(code(perform(post(path("/members/" + teamAdmin + "/suspend")), orgAdmin), 409))
                .isEqualTo("LAST_ADMIN_REQUIRED");
        assertThat(code(perform(post(path("/members/" + teamAdmin + "/remove")), orgAdmin), 409))
                .isEqualTo("LAST_ADMIN_REQUIRED");
        assertThat(teamLog("TEAM_MEMBER_SUSPENDED")).containsExactly(teamAdmin + " " + member + " ACTIVE SUSPENDED");
        assertThat(teamLog("TEAM_MEMBER_REMOVED")).containsExactly(teamAdmin + " " + member + " ACTIVE LEFT");
    }

    @Test
    @DisplayName("TEN-TMEM-04: 조직에서 정지된 팀 관리자는 세지 않아 강등 409, 보관한 팀은 쓰기 409 READ_ONLY")
    void countsOnlyActiveOrgMembers() throws Exception {
        UUID other = newAccount("other");
        insertOrgMembership(other, "MEMBER", "SUSPENDED");
        insertTeamMembership(teamId, other, "TEAM_ADMIN", "ACTIVE");

        assertThat(code(perform(put(path("/members/" + teamAdmin + "/role")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"TEAM_MEMBER\",\"version\":0}"), orgAdmin), 409)).isEqualTo("LAST_ADMIN_REQUIRED");

        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :id").param("id", teamId).update();
        assertThat(code(add(orgAdmin, candidate, "TEAM_MEMBER"), 409)).isEqualTo("READ_ONLY");
        assertThat(code(perform(post(path("/members/" + member + "/suspend")), orgAdmin), 409)).isEqualTo("READ_ONLY");
        assertThat(perform(get(path("/members")), orgAdmin).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-SESS-10·API-TADM-03: 최근 인증 지나면 401, 다른 팀의 팀원은 내 팀 경로로 404, 팀원·읽기 전용 403")
    void requiresRecentAuthAndIsolates() throws Exception {
        Instant stale = Instant.now().minus(Duration.ofHours(2));
        for (String action : List.of("/suspend", "/unsuspend", "/remove")) {
            assertThat(code(perform(post(path("/members/" + member + action)), teamAdmin, stale), 401)).as(action)
                    .isEqualTo("REAUTH_REQUIRED");
            assertThat(perform(post(path("/members/" + viewer + action)), member).getStatus()).as(action)
                    .isEqualTo(403);
        }
        assertThat(code(perform(put(path("/members/" + member + "/role")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"VIEWER\",\"version\":0}"), teamAdmin, stale), 401)).isEqualTo("REAUTH_REQUIRED");

        String otherTeam = createTeam("다른 팀", candidate);
        for (String action : List.of("/suspend", "/unsuspend", "/remove")) {
            assertThat(act(candidate, action)).as(action).isEqualTo(404);
        }
        assertThat(changeRole(teamAdmin, candidate, "VIEWER", 0)).isEqualTo(404);
        assertThat(perform(get("/api/teams/" + otherTeam + "/members"), teamAdmin).getStatus()).isEqualTo(404);
        assertThat(perform(get(path("/activity")), member).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("T-AUTHZ-LASTADMIN: 팀 관리자 둘이 동시에 서로를 강등·정지·내보내도 활동 중인 팀 관리자가 남음")
    void keepsLastTeamAdminUnderConcurrency() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (String action : List.of("role", "suspend", "remove")) {
                for (int round = 0; round < 3; round++) {
                    jdbc.sql("""
                                    UPDATE team_membership SET role = 'TEAM_ADMIN', state = 'ACTIVE', left_reason = NULL
                                    WHERE team_id = :team AND account_id IN (:accounts)
                                    """)
                            .param("team", teamId)
                            .param("accounts", List.of(teamAdmin.toString(), member.toString()))
                            .update();
                    CountDownLatch start = new CountDownLatch(1);
                    CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                            () -> race(start, teamAdmin, member, action), executor);
                    CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                            () -> race(start, member, teamAdmin, action), executor);
                    start.countDown();
                    List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS),
                            second.get(30, TimeUnit.SECONDS));

                    assertThat(statuses).as(action).containsOnlyOnce(204);
                    assertThat(statuses).as(action).allMatch(status -> List.of(204, 403, 404, 409).contains(status));
                    assertThat(jdbc.sql("""
                                    SELECT COUNT(*) FROM team_membership
                                    WHERE team_id = :team AND role = 'TEAM_ADMIN' AND state = 'ACTIVE'
                                    """)
                            .param("team", teamId).query(Integer.class).single()).as(action).isOne();
                }
            }
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("TEN-TMEM-07: 팀 활동 기록은 그 팀 기록만 최신부터, 이름 붙임, 거르기·커서, 행동 형식 400")
    void listsTeamActivity() throws Exception {
        assertThat(act(member, "/suspend")).isEqualTo(204);
        assertThat(add(teamAdmin, candidate, "VIEWER").getStatus()).isEqualTo(201);

        JsonNode all = json(perform(get(path("/activity")), teamAdmin), 200);

        assertThat(values(all, "action")).startsWith("TEAM_MEMBER_ADDED", "TEAM_MEMBER_SUSPENDED");
        assertThat(all.get("items").get(0).get("actorName").asString()).isEqualTo("teamadmin");
        assertThat(all.get("items").get(0).get("targetName").asString()).isEqualTo("candidate");
        assertThat(values(json(perform(get(path("/activity?action=TEAM_MEMBER_SUSPENDED")), orgAdmin), 200),
                "action")).containsExactly("TEAM_MEMBER_SUSPENDED");
        assertThat(json(perform(get(path("/activity?actorId=" + member)), teamAdmin), 200).get("items").isEmpty())
                .isTrue();
        assertThat(perform(get(path("/activity?action=bad")), teamAdmin).getStatus()).isEqualTo(400);
        assertThat(perform(get(path("/activity?cursor=abc.def")), teamAdmin).getStatus()).isEqualTo(400);
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(perform(get(path("/activity?limit=1" + (cursor == null ? "" : "&cursor=" + cursor))),
                    teamAdmin), 200);
            paged.addAll(values(page, "id"));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);
        assertThat(paged).isEqualTo(values(all, "id"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM team_activity_log WHERE team_id = :team").param("team", teamId)
                .query(Integer.class).single()).isEqualTo(paged.size());
    }

    private int race(CountDownLatch start, UUID actor, UUID target, String action) {
        try {
            start.await();
            MockHttpServletRequestBuilder request = "role".equals(action)
                    ? put(path("/members/" + target + "/role")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"TEAM_MEMBER\",\"version\":" + version(target) + "}")
                    : post(path("/members/" + target + "/" + action));
            return perform(request, actor).getStatus();
        }
        catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private int act(UUID target, String action) throws Exception {
        return perform(post(path("/members/" + target + action)), teamAdmin).getStatus();
    }

    private int changeRole(UUID actor, UUID target, String role, int version) throws Exception {
        return perform(put(path("/members/" + target + "/role")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\",\"version\":" + version + "}"), actor).getStatus();
    }

    private MockHttpServletResponse add(UUID actor, UUID account, String role) throws Exception {
        return perform(post(path("/members")).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("accountId", account.toString(), "role", role))), actor);
    }

    private int version(UUID account) {
        return jdbc.sql("SELECT version FROM team_membership WHERE team_id = :team AND account_id = :account")
                .param("team", teamId).param("account", account.toString()).query(Integer.class).single();
    }

    private String path(String suffix) {
        return "/api/teams/" + teamId + suffix;
    }

    private String createTeam(String name, UUID firstAdmin) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", orgId).query(String.class).single());
        body.put("firstAdminAccountId", firstAdmin.toString());
        return json(perform(post("/api/orgs/" + orgId + "/teams")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), orgAdmin), 201)
                .get("teamId").asString();
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
        return perform(request, actor, Instant.now());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor, Instant authenticatedAt)
            throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, authenticatedAt)))
                .with(csrfToken())).andReturn().getResponse();
    }

    private void insertOrgMembership(UUID account, String kind, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version)
                        VALUES (:id, :org, :account, :kind, :admin, :state, 'INVITATION', :expires, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", account.toString())
                .param("kind", kind)
                .param("admin", false)
                .param("state", state)
                .param("expires", "GUEST".equals(kind) ? now.plusDays(30) : null)
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String team, UUID account, String role, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, created_at,
                            updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'DIRECT', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", team)
                .param("account", account.toString())
                .param("role", role)
                .param("state", state)
                .param("now", now)
                .update();
    }

    private List<String> teamLog(String action) {
        return jdbc.sql("""
                        SELECT actor_id, target_id, before_value, after_value FROM team_activity_log
                        WHERE team_id = :team AND action = :action ORDER BY occurred_at
                        """)
                .param("team", teamId).param("action", action)
                .query((rs, rowNum) -> rs.getString("actor_id") + " " + rs.getString("target_id") + " "
                        + valueOrDash(rs.getString("before_value")) + " " + valueOrDash(rs.getString("after_value")))
                .list();
    }

    private static String valueOrDash(String value) {
        return value == null ? "-" : value;
    }

    private static List<String> accountIds(JsonNode page) {
        return values(page, "accountId");
    }

    private static List<String> values(JsonNode page, String field) {
        List<String> values = new ArrayList<>();
        page.get("items").forEach(item -> values.add(item.get(field).isNull() ? null : item.get(field).asString()));
        return values;
    }

    private List<String> fields(MockHttpServletResponse response) throws Exception {
        List<String> fields = new ArrayList<>();
        json(response, 400).get("fields")
                .forEach(field -> fields.add(field.get("field").asString() + ":" + field.get("reason").asString()));
        return fields;
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
