package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class JoinRequestReviewTest {

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
    private UUID outsider;
    private String orgId;
    private String publicTeam;
    private String privateTeam;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : InviteLinkTest.TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        orgAdmin = newAccount("orgadmin@example.com");
        teamAdmin = newAccount("teamadmin@example.com");
        member = newAccount("haneul@example.com");
        outsider = newAccount("outsider@example.com");
        orgId = createOrg(orgAdmin, "새벽 서재");
        jdbc.sql("""
                        UPDATE organization SET visibility = 'PUBLIC', join_request_mode = 'APPROVAL',
                            join_link_enabled = TRUE
                        WHERE id = :org
                        """)
                .param("org", orgId).update();
        insertOrgMembership(orgId, member, "ACTIVE");
        insertOrgMembership(orgId, teamAdmin, "ACTIVE");
        publicTeam = createTeam("독서 모임", "PUBLIC");
        privateTeam = createTeam("운영 팀", "PRIVATE");
        insertTeamMembership(privateTeam, teamAdmin, "TEAM_ADMIN", "ACTIVE");
    }

    @Test
    @DisplayName("TEN-REQ-14·17: 조직 목록은 확인 대기만, 팀 목록은 그 팀 요청과 확인 대기 표시, 이름만, 오래된 순 커서")
    void listsRequests() throws Exception {
        UUID first = newAccount("first@example.com");
        json(joinOrg(first), 200);
        json(redeem(outsider, link(privateTeam, teamAdmin, true)), 200);
        UUID linked = newAccount("linked@example.com");
        json(redeem(linked, link(privateTeam, orgAdmin, true)), 200);

        JsonNode page = json(perform(get(orgRequests()).param("limit", "1"), orgAdmin), 200);
        assertThat(page.get("items")).hasSize(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("accountId").asString()).isEqualTo(first.toString());
        assertThat(item.get("displayName").asString()).isEqualTo("first");
        assertThat(item.get("joinedVia").asString()).isEqualTo("REQUEST");
        assertThat(item.get("teams")).isEmpty();
        assertThat(fields(item)).containsExactlyInAnyOrder("accountId", "displayName", "joinedVia", "requestedAt",
                "teams");
        JsonNode next = json(perform(get(orgRequests()).param("cursor", page.get("nextCursor").asString()),
                orgAdmin), 200);
        assertThat(next.get("items")).hasSize(1);
        JsonNode second = next.get("items").get(0);
        assertThat(second.get("accountId").asString()).isEqualTo(outsider.toString());
        assertThat(second.get("joinedVia").asString()).isEqualTo("LINK");
        assertThat(second.get("teams").get(0).get("name").asString()).isEqualTo("운영 팀");
        assertThat(second.get("teams").get(0).get("role").asString()).isEqualTo("TEAM_MEMBER");
        assertThat(next.get("nextCursor").isNull()).isTrue();
        assertThat(perform(get(orgRequests()).param("cursor", "broken"), orgAdmin).getStatus()).isEqualTo(400);

        JsonNode team = json(perform(get(teamRequests(privateTeam)), teamAdmin), 200);
        List<String> pending = new ArrayList<>();
        team.get("items").forEach(row -> pending.add(row.get("displayName").asString() + " "
                + row.get("orgReviewPending").asBoolean()));
        assertThat(pending).containsExactly("outsider true", "linked false");
        assertThat(fields(team.get("items").get(0))).containsExactlyInAnyOrder("accountId", "displayName", "role",
                "joinedVia", "requestedAt", "orgReviewPending");
        assertThat(json(perform(get("/api/orgs/" + orgId + "/overview"), orgAdmin), 200).get("pendingJoinRequests")
                .asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("T-ISO-ORG·T-ISO-TEAM: 조직·팀 가입 요청 API는 관리자만, 다른 조직 관리자는 404")
    void onlyAdminsReview() throws Exception {
        json(joinOrg(outsider), 200);
        UUID stranger = newAccount("stranger@example.com");
        createOrg(stranger, "다른 서재");

        assertThat(perform(get(orgRequests()), member).getStatus()).isEqualTo(403);
        assertThat(perform(get(orgRequests()), teamAdmin).getStatus()).isEqualTo(403);
        assertThat(perform(get(orgRequests()), stranger).getStatus()).isEqualTo(404);
        assertThat(perform(post(orgRequests() + "/" + outsider + "/approve"), member).getStatus()).isEqualTo(403);
        assertThat(perform(post(orgRequests() + "/" + outsider + "/reject"), stranger).getStatus()).isEqualTo(404);
        assertThat(perform(get(teamRequests(privateTeam)), member).getStatus()).isEqualTo(404);
        assertThat(perform(get(teamRequests(publicTeam)), teamAdmin).getStatus()).isEqualTo(404);
        insertTeamMembership(publicTeam, teamAdmin, "TEAM_MEMBER", "ACTIVE");
        assertThat(perform(get(teamRequests(publicTeam)), teamAdmin).getStatus()).isEqualTo(403);
        assertThat(perform(get(teamRequests(publicTeam)), stranger).getStatus()).isEqualTo(404);
        assertThat(orgMembership(outsider)).isEqualTo("REQUESTED MEMBER true");
    }

    @Test
    @DisplayName("T-ISO-TEAM: 팀 승인·거절은 팀 구성원·VIEWER 403, 다른 조직 관리자 404, 요청·멤버십·활동 기록 그대로")
    void onlyAdminsDecideTeamRequests() throws Exception {
        UUID peer = newAccount("peer@example.com");
        UUID viewer = newAccount("viewer@example.com");
        UUID stranger = newAccount("stranger@example.com");
        createOrg(stranger, "다른 서재");
        insertOrgMembership(orgId, peer, "ACTIVE");
        insertOrgMembership(orgId, viewer, "ACTIVE");
        insertTeamMembership(publicTeam, peer, "TEAM_MEMBER", "ACTIVE");
        insertTeamMembership(publicTeam, viewer, "VIEWER", "ACTIVE");
        json(perform(post("/api/orgs/" + orgId + "/teams/" + publicTeam + "/join"), member), 200);

        for (String action : List.of("approve", "reject")) {
            String url = teamRequests(publicTeam) + "/" + member + "/" + action;
            assertThat(perform(post(url), peer).getStatus()).as(action).isEqualTo(403);
            assertThat(perform(post(url), viewer).getStatus()).as(action).isEqualTo(403);
            assertThat(perform(post(url), stranger).getStatus()).as(action).isEqualTo(404);
        }
        assertThat(teamMembership(publicTeam, member)).isEqualTo("REQUESTED TEAM_MEMBER");
        assertThat(rejectedAt("team_membership", "team_id", publicTeam, member)).isNull();
        assertThat(orgMembership(member)).isEqualTo("ACTIVE MEMBER false");
        for (UUID actor : List.of(peer, viewer, stranger)) {
            assertThat(log("team_activity_log", "TEAM_MEMBER_APPROVED", actor)).isEmpty();
            assertThat(log("team_activity_log", "TEAM_MEMBER_REJECTED", actor)).isEmpty();
        }
    }

    @Test
    @DisplayName("TEN-TST-15·TEN-REQ-15·18: 팀 관리자 링크 새 구성원은 조직 관리자 승인 전 팀 승인 403, 조직 승인 뒤 링크대로")
    void teamAdminLinksWaitForOrgAdmin() throws Exception {
        json(redeem(outsider, link(privateTeam, teamAdmin, false)), 200);
        assertThat(orgMembership(outsider)).isEqualTo("REQUESTED MEMBER true");
        assertThat(teamMembership(privateTeam, outsider)).isEqualTo("REQUESTED TEAM_MEMBER");

        MockHttpServletResponse blocked = perform(post(teamRequests(privateTeam) + "/" + outsider + "/approve"),
                teamAdmin);
        assertThat(json(blocked, 403).get("code").asString()).isEqualTo("FORBIDDEN_ACTION");
        assertThat(orgMembership(outsider)).isEqualTo("REQUESTED MEMBER true");

        assertThat(perform(post(orgRequests() + "/" + outsider + "/approve"), orgAdmin).getStatus()).isEqualTo(204);
        assertThat(orgMembership(outsider)).isEqualTo("ACTIVE MEMBER false");
        assertThat(teamMembership(privateTeam, outsider)).isEqualTo("ACTIVE TEAM_MEMBER");
        assertThat(inviteLinkOf(privateTeam, outsider)).isNotNull();
        assertThat(log("org_activity_log", "ORG_MEMBER_APPROVED", orgAdmin)).containsExactly("REQUESTED MEMBER");
        assertThat(log("team_activity_log", "TEAM_MEMBER_APPROVED", orgAdmin))
                .containsExactly("REQUESTED TEAM_MEMBER");
        assertThat(perform(get("/api/teams/" + privateTeam), outsider).getStatus()).isEqualTo(200);
        assertThat(json(perform(post(orgRequests() + "/" + outsider + "/approve"), orgAdmin), 409).get("code")
                .asString()).isEqualTo("ALREADY_PROCESSED");

        UUID careful = newAccount("careful@example.com");
        json(redeem(careful, link(privateTeam, teamAdmin, true)), 200);
        assertThat(perform(post(orgRequests() + "/" + careful + "/approve"), orgAdmin).getStatus()).isEqualTo(204);
        assertThat(orgMembership(careful)).isEqualTo("ACTIVE MEMBER false");
        assertThat(teamMembership(privateTeam, careful)).isEqualTo("REQUESTED TEAM_MEMBER");
        assertThat(perform(post(teamRequests(privateTeam) + "/" + careful + "/approve"), teamAdmin).getStatus())
                .isEqualTo(204);
        assertThat(teamMembership(privateTeam, careful)).isEqualTo("ACTIVE TEAM_MEMBER");
    }

    @Test
    @DisplayName("TEN-REQ-15: 조직 승인에서 팀·역할을 고르면 그 팀에 넣고, 넣을 수 없는 팀이면 400·아무것도 안 바뀜")
    void approvalPlacesTeam() throws Exception {
        json(joinOrg(outsider), 200);
        UUID stranger = newAccount("stranger@example.com");
        String otherOrg = createOrg(stranger, "다른 서재");
        String otherTeam = jdbc.sql("SELECT id FROM team WHERE org_id = :org").param("org", otherOrg)
                .query(String.class).single();
        String archived = createTeam("쉬는 팀", "PRIVATE");
        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :team").param("team", archived).update();
        String approve = orgRequests() + "/" + outsider + "/approve";

        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of("role", "VIEWER"),
                Map.of("teamId", otherTeam), Map.of("teamId", archived),
                Map.of("teamId", publicTeam, "role", "TEAM_ADMIN"))) {
            assertThat(perform(post(approve).contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(body)), orgAdmin).getStatus()).as(body.toString())
                    .isEqualTo(400);
        }
        assertThat(orgMembership(outsider)).isEqualTo("REQUESTED MEMBER true");
        assertThat(log("org_activity_log", "ORG_MEMBER_APPROVED", orgAdmin)).isEmpty();

        assertThat(perform(post(approve).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("teamId", privateTeam, "role", "VIEWER"))), orgAdmin)
                .getStatus()).isEqualTo(204);
        assertThat(orgMembership(outsider)).isEqualTo("ACTIVE MEMBER false");
        assertThat(teamMembership(privateTeam, outsider)).isEqualTo("ACTIVE VIEWER");
        assertThat(log("team_activity_log", "TEAM_MEMBER_ADDED", orgAdmin)).contains("null VIEWER");

        UUID suspended = newAccount("suspended@example.com");
        json(joinOrg(suspended), 200);
        insertTeamMembership(publicTeam, suspended, "TEAM_MEMBER", "SUSPENDED");
        assertThat(perform(post(orgRequests() + "/" + suspended + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("teamId", publicTeam))), orgAdmin).getStatus())
                .isEqualTo(400);
        assertThat(orgMembership(suspended)).isEqualTo("REQUESTED MEMBER true");
    }

    @Test
    @DisplayName("TEN-REQ-16·17: 조직 거절은 7일 대기, 링크 팀 요청도 거절(대기 없음). 확인 없는 요청·없는 계정은 404")
    void orgRejection() throws Exception {
        json(redeem(outsider, link(privateTeam, teamAdmin, true)), 200);

        assertThat(perform(post(orgRequests() + "/" + outsider + "/reject"), orgAdmin).getStatus()).isEqualTo(204);
        assertThat(orgMembership(outsider)).isEqualTo("REJECTED MEMBER false");
        assertThat(rejectedAt("org_membership", "org_id", orgId, outsider)).isNotNull();
        assertThat(teamMembership(privateTeam, outsider)).isEqualTo("REJECTED TEAM_MEMBER");
        assertThat(rejectedAt("team_membership", "team_id", privateTeam, outsider)).isNull();
        assertThat(log("org_activity_log", "ORG_MEMBER_REJECTED", orgAdmin)).containsExactly("REQUESTED REJECTED");
        assertThat(log("team_activity_log", "TEAM_MEMBER_REJECTED", orgAdmin)).containsExactly("REQUESTED REJECTED");
        assertThat(json(joinOrg(outsider), 409).get("code").asString()).isEqualTo("REQUEST_COOLDOWN");
        assertThat(json(perform(post(orgRequests() + "/" + outsider + "/reject"), orgAdmin), 409).get("code")
                .asString()).isEqualTo("ALREADY_PROCESSED");

        UUID linked = newAccount("linked@example.com");
        json(redeem(linked, link(privateTeam, orgAdmin, true)), 200);
        assertThat(perform(post(orgRequests() + "/" + linked + "/approve"), orgAdmin).getStatus()).isEqualTo(404);
        assertThat(perform(post(orgRequests() + "/" + linked + "/reject"), orgAdmin).getStatus()).isEqualTo(404);
        assertThat(perform(post(orgRequests() + "/" + UUID.randomUUID() + "/reject"), orgAdmin).getStatus())
                .isEqualTo(404);
        assertThat(orgMembership(linked)).isEqualTo("REQUESTED MEMBER false");
    }

    @Test
    @DisplayName("TEN-REQ-18·20: 확인 없는 링크 요청은 팀 승인이 조직 멤버십도 ACTIVE, 공개 팀 요청은 팀만")
    void teamApproval() throws Exception {
        json(redeem(outsider, link(privateTeam, orgAdmin, true)), 200);

        assertThat(perform(post(teamRequests(privateTeam) + "/" + outsider + "/approve"), teamAdmin).getStatus())
                .isEqualTo(204);
        assertThat(orgMembership(outsider)).isEqualTo("ACTIVE MEMBER false");
        assertThat(teamMembership(privateTeam, outsider)).isEqualTo("ACTIVE TEAM_MEMBER");
        assertThat(inviteLinkOf(privateTeam, outsider)).isNotNull();
        assertThat(log("org_activity_log", "ORG_MEMBER_APPROVED", teamAdmin)).containsExactly("REQUESTED MEMBER");
        assertThat(log("team_activity_log", "TEAM_MEMBER_APPROVED", teamAdmin))
                .containsExactly("REQUESTED TEAM_MEMBER");

        json(perform(post("/api/orgs/" + orgId + "/teams/" + publicTeam + "/join"), member), 200);
        assertThat(perform(post(teamRequests(publicTeam) + "/" + member + "/approve"), orgAdmin).getStatus())
                .isEqualTo(204);
        assertThat(teamMembership(publicTeam, member)).isEqualTo("ACTIVE TEAM_MEMBER");
        assertThat(log("org_activity_log", "ORG_MEMBER_APPROVED", orgAdmin)).isEmpty();
        assertThat(json(perform(post(teamRequests(publicTeam) + "/" + member + "/approve"), orgAdmin), 409)
                .get("code").asString()).isEqualTo("ALREADY_PROCESSED");
        assertThat(perform(post(teamRequests(publicTeam) + "/" + UUID.randomUUID() + "/approve"), orgAdmin)
                .getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("TEN-REQ-19: 팀 거절은 7일 대기, 확인 없는 조직 요청은 다른 팀 요청이 없을 때만 함께 거절(대기 없음)")
    void teamRejection() throws Exception {
        json(redeem(outsider, link(privateTeam, orgAdmin, true)), 200);
        json(redeem(outsider, link(publicTeam, orgAdmin, true)), 200);

        assertThat(perform(post(teamRequests(privateTeam) + "/" + outsider + "/reject"), teamAdmin).getStatus())
                .isEqualTo(204);
        assertThat(teamMembership(privateTeam, outsider)).isEqualTo("REJECTED TEAM_MEMBER");
        assertThat(rejectedAt("team_membership", "team_id", privateTeam, outsider)).isNotNull();
        assertThat(orgMembership(outsider)).isEqualTo("REQUESTED MEMBER false");

        assertThat(perform(post(teamRequests(publicTeam) + "/" + outsider + "/reject"), orgAdmin).getStatus())
                .isEqualTo(204);
        assertThat(orgMembership(outsider)).isEqualTo("REJECTED MEMBER false");
        assertThat(rejectedAt("org_membership", "org_id", orgId, outsider)).isNull();
        assertThat(log("org_activity_log", "ORG_MEMBER_REJECTED", orgAdmin)).containsExactly("REQUESTED REJECTED");
        assertThat(log("team_activity_log", "TEAM_MEMBER_REJECTED", teamAdmin)).containsExactly("REQUESTED REJECTED");

        json(perform(post("/api/orgs/" + orgId + "/teams/" + publicTeam + "/join"), member), 200);
        assertThat(perform(post(teamRequests(publicTeam) + "/" + member + "/reject"), orgAdmin).getStatus())
                .isEqualTo(204);
        assertThat(orgMembership(member)).isEqualTo("ACTIVE MEMBER false");
        assertThat(json(perform(post("/api/orgs/" + orgId + "/teams/" + publicTeam + "/join"), member), 409)
                .get("code").asString()).isEqualTo("REQUEST_COOLDOWN");
    }

    @Test
    @DisplayName("TEN-REQ-17: 같은 요청을 동시에 승인·거절하면 하나만 성공")
    void concurrentDecisionsSucceedOnce() throws Exception {
        json(joinOrg(outsider), 200);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> decisions = List.of("approve", "reject").stream()
                    .<Callable<Integer>>map(action -> () -> {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            return -1;
                        }
                        return perform(post(orgRequests() + "/" + outsider + "/" + action), orgAdmin).getStatus();
                    })
                    .toList();
            List<Future<Integer>> results = decisions.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        }
        finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        assertThat(log("org_activity_log", "ORG_MEMBER_APPROVED", orgAdmin).size()
                + log("org_activity_log", "ORG_MEMBER_REJECTED", orgAdmin).size()).isOne();
    }

    private String orgRequests() {
        return "/api/orgs/" + orgId + "/join-requests";
    }

    private static String teamRequests(String team) {
        return "/api/teams/" + team + "/join-requests";
    }

    private MockHttpServletResponse joinOrg(UUID actor) throws Exception {
        return perform(post("/api/public-orgs/" + orgId + "/join"), actor);
    }

    private MockHttpServletResponse redeem(UUID actor, String token) throws Exception {
        return perform(post("/api/invite-links/redeem").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("token", token))), actor);
    }

    private String link(String team, UUID creator, boolean approvalRequired) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvalRequired", approvalRequired);
        body.put("role", "TEAM_MEMBER");
        body.put("maxUses", 10);
        String url = json(perform(post("/api/teams/" + team + "/invite-links")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), creator), 201)
                .get("url").asString();
        return url.substring(url.indexOf('#') + 1);
    }

    private static List<String> fields(JsonNode item) {
        List<String> fields = new ArrayList<>();
        item.propertyNames().forEach(fields::add);
        return fields;
    }

    private LocalDateTime rejectedAt(String table, String scope, String scopeId, UUID account) {
        return jdbc.sql("SELECT rejected_at FROM " + table + " WHERE " + scope + " = :scope AND account_id = :account")
                .param("scope", scopeId).param("account", account.toString())
                .query((rs, rowNum) -> rs.getObject(1, LocalDateTime.class)).list().get(0);
    }

    private String inviteLinkOf(String team, UUID account) {
        return jdbc.sql("SELECT invite_link_id FROM team_membership WHERE team_id = :team AND account_id = :account")
                .param("team", team).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString(1)).list().get(0);
    }

    private String orgMembership(UUID account) {
        return jdbc.sql("""
                        SELECT state, kind, admission_review FROM org_membership
                        WHERE org_id = :org AND account_id = :account
                        """)
                .param("org", orgId).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + " " + rs.getString("kind") + " "
                        + rs.getBoolean("admission_review"))
                .optional().orElse(null);
    }

    private String teamMembership(String team, UUID account) {
        return jdbc.sql("SELECT state, role FROM team_membership WHERE team_id = :team AND account_id = :account")
                .param("team", team).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + " " + rs.getString("role"))
                .optional().orElse(null);
    }

    private List<String> log(String table, String action, UUID actor) {
        return jdbc.sql("SELECT before_value, after_value FROM " + table
                        + " WHERE action = :action AND actor_id = :actor ORDER BY occurred_at, id")
                .param("action", action).param("actor", actor.toString())
                .query((rs, rowNum) -> rs.getString("before_value") + " " + rs.getString("after_value"))
                .list();
    }

    private void insertOrgMembership(String org, UUID account, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version, admission_review)
                        VALUES (:id, :org, :account, 'MEMBER', FALSE, :state, 'INVITATION', NULL, :now, :now, 0, FALSE)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", org)
                .param("account", account.toString())
                .param("state", state)
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String team, UUID account, String role, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, created_at,
                            updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'INVITATION', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", team)
                .param("account", account.toString())
                .param("role", role)
                .param("state", state)
                .param("now", now)
                .update();
    }

    private String createTeam(String name, String visibility) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", visibility);
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", orgId).query(String.class).single());
        body.put("firstAdminAccountId", orgAdmin.toString());
        return json(perform(post("/api/orgs/" + orgId + "/teams")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), orgAdmin), 201)
                .get("teamId").asString();
    }

    private String createOrg(UUID actor, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("timeZone", "Asia/Seoul");
        body.put("firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"));
        return json(perform(post("/api/orgs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), actor), 201)
                .get("orgId").asString();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return jsonMapper.readTree(response.getContentAsString());
    }

    private UUID newAccount(String email) {
        String name = email.substring(0, email.indexOf('@')).toLowerCase();
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(), email, name,
                null, null), "v1", "v1", Instant.now());
    }

}
