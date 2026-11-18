package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
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
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InviteLinkRedeemTest {

    private static final Duration FAILURE_DELAY = Duration.ofMillis(300);

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
    private String teamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : InviteLinkTest.TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        orgAdmin = newAccount("orgadmin@example.com");
        teamAdmin = newAccount("teamadmin@example.com");
        member = newAccount("haneul@example.com");
        outsider = newAccount("outsider@example.com");
        orgId = json(createOrg(orgAdmin, "새벽 서재"), 201).get("orgId").asString();
        insertOrgMembership(teamAdmin, "MEMBER", "ACTIVE");
        insertOrgMembership(member, "MEMBER", "ACTIVE");
        teamId = createTeam("개발 팀", teamAdmin);
        addTeamMember(member);
        jdbc.sql("UPDATE organization SET join_link_enabled = :on WHERE id = :org").param("on", true)
                .param("org", orgId).update();
    }

    @Test
    @DisplayName("API-LINK-02: 로그인하지 않은 요청은 토큰을 보지 않고 401")
    void unauthenticatedIsRejected() throws Exception {
        String token = token(create(orgAdmin, false, "VIEWER", 3));

        MockHttpServletResponse response = mockMvc.perform(post("/api/invite-links/redeem").with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content(body(token))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(useCount(token)).isZero();
    }

    @Test
    @DisplayName("§7.4·TEN-LINK-01: 승인 없는 조직 관리자 링크로 조직 밖 사람이 바로 구성원·팀원, 사용 횟수 +1, 팀 첫 화면")
    void outsiderJoins() throws Exception {
        String token = token(create(orgAdmin, false, "VIEWER", 3));

        JsonNode joined = json(redeem(outsider, token), 200);

        assertThat(joined.get("result").asString()).isEqualTo("JOINED");
        assertThat(joined.get("next").get("screen").asString()).isEqualTo("TEAM_HOME");
        assertThat(joined.get("next").get("teamId").asString()).isEqualTo(teamId);
        assertThat(joined.get("orgName").asString()).isEqualTo("새벽 서재");
        assertThat(joined.get("teamName").asString()).isEqualTo("개발 팀");
        assertThat(orgMembership(outsider)).isEqualTo("ACTIVE MEMBER LINK false");
        assertThat(teamMembership(outsider)).isEqualTo("ACTIVE VIEWER LINK " + linkId(token));
        assertThat(useCount(token)).isOne();
        assertThat(log("org_activity_log", "ORG_MEMBER_JOINED", outsider)).containsExactly("null MEMBER");
        assertThat(log("team_activity_log", "TEAM_MEMBER_JOINED", outsider)).containsExactly("null VIEWER");
        assertThat(log("team_activity_log", "INVITE_LINK_USED", outsider)).containsExactly("null JOINED");
    }

    @Test
    @DisplayName("§7.4: 이미 그 팀 ACTIVE 팀원이면 ALREADY_MEMBER, 사용 횟수를 늘리지 않음")
    void activeMemberIsAlreadyMember() throws Exception {
        String token = token(create(teamAdmin, false, "TEAM_MEMBER", 1));

        JsonNode result = json(redeem(member, token), 200);

        assertThat(result.get("result").asString()).isEqualTo("ALREADY_MEMBER");
        assertThat(result.get("next").get("teamId").asString()).isEqualTo(teamId);
        assertThat(useCount(token)).isZero();
        assertThat(json(redeem(member, token), 200).get("result").asString()).isEqualTo("ALREADY_MEMBER");
    }

    @Test
    @DisplayName("§7.4·API-LINK-03: 승인 필요 링크는 조직·팀 REQUESTED, 이름 없이 A2, 다시 열어도 횟수 그대로")
    void approvalLinkRequests() throws Exception {
        String token = token(create(orgAdmin, true, "TEAM_MEMBER", 5));

        MockHttpServletResponse response = redeem(outsider, token);
        JsonNode requested = json(response, 200);

        assertThat(requested.get("result").asString()).isEqualTo("REQUESTED");
        assertThat(requested.get("next").get("screen").asString()).isEqualTo("JOIN_PENDING");
        assertThat(requested.has("orgName")).isFalse();
        assertThat(requested.has("teamName")).isFalse();
        assertThat(response.getContentAsString()).doesNotContain("새벽 서재").doesNotContain("개발 팀");
        assertThat(orgMembership(outsider)).isEqualTo("REQUESTED MEMBER LINK false");
        assertThat(teamMembership(outsider)).isEqualTo("REQUESTED TEAM_MEMBER LINK " + linkId(token));
        assertThat(useCount(token)).isOne();
        assertThat(log("org_activity_log", "ORG_MEMBER_REQUESTED", outsider)).containsExactly("null REQUESTED");
        assertThat(log("team_activity_log", "TEAM_MEMBER_REQUESTED", outsider)).containsExactly("null TEAM_MEMBER");

        assertThat(json(redeem(outsider, token), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(useCount(token)).isOne();
    }

    @Test
    @DisplayName("§7.4·TEN-ADM-01: 팀 관리자 링크는 확인이 켜져 있으면 조직 밖 사람을 REQUESTED로, 꺼져 있으면 바로 들임")
    void teamAdminLinkFollowsAdmissionReview() throws Exception {
        String token = token(create(teamAdmin, false, "VIEWER", 5));

        assertThat(json(redeem(outsider, token), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(orgMembership(outsider)).isEqualTo("REQUESTED MEMBER LINK false");
        assertThat(teamMembership(outsider)).isEqualTo("REQUESTED VIEWER LINK " + linkId(token));

        UUID existing = newAccount("sol@example.com");
        insertOrgMembership(existing, "MEMBER", "ACTIVE");
        assertThat(json(redeem(existing, token), 200).get("result").asString()).isEqualTo("JOINED");
        assertThat(teamMembership(existing)).isEqualTo("ACTIVE VIEWER LINK " + linkId(token));

        jdbc.sql("UPDATE organization SET member_admission_review = :off WHERE id = :org").param("off", false)
                .param("org", orgId).update();
        UUID another = newAccount("dawn@example.com");
        assertThat(json(redeem(another, token), 200).get("result").asString()).isEqualTo("JOINED");
        assertThat(orgMembership(another)).isEqualTo("ACTIVE MEMBER LINK false");
        assertThat(useCount(token)).isEqualTo(3);
    }

    @Test
    @DisplayName("§7.4: 끝난 조직·팀 멤버십(LEFT·REJECTED·EXPIRED, 끝난 손님)은 링크로 다시 MEMBER·ACTIVE")
    void endedMembershipsRejoin() throws Exception {
        String token = token(create(orgAdmin, false, "TEAM_MEMBER", 5));
        UUID left = newAccount("left@example.com");
        insertOrgMembership(left, "MEMBER", "LEFT");
        insertTeamMembership(left, "VIEWER", "LEFT");
        UUID formerGuest = newAccount("guest@example.com");
        insertOrgMembership(formerGuest, "GUEST", "EXPIRED");

        assertThat(json(redeem(left, token), 200).get("result").asString()).isEqualTo("JOINED");
        assertThat(orgMembership(left)).isEqualTo("ACTIVE MEMBER INVITATION false");
        assertThat(teamMembership(left)).isEqualTo("ACTIVE TEAM_MEMBER INVITATION " + linkId(token));
        assertThat(log("team_activity_log", "TEAM_MEMBER_JOINED", left)).containsExactly("LEFT TEAM_MEMBER");
        assertThat(json(redeem(formerGuest, token), 200).get("result").asString()).isEqualTo("JOINED");
        assertThat(orgMembership(formerGuest)).isEqualTo("ACTIVE MEMBER INVITATION false");
    }

    @Test
    @DisplayName("TEN-LINK-05·06·API-LINK-01·SEC-TOKEN-06·T-TOKEN: 모든 실패는 같은 404 본문, 300ms 아래로 끝나지 않음, 사용 횟수 그대로")
    void everyFailureLooksTheSame() throws Exception {
        List<String> bodies = new ArrayList<>();
        List<Duration> durations = new ArrayList<>();
        Map<String, Runnable> cases = new LinkedHashMap<>();
        String good = token(create(orgAdmin, false, "VIEWER", 5));
        cases.put("형식 오류", () -> { });
        cases.put("없는 토큰", () -> { });
        String disabled = token(create(orgAdmin, false, "VIEWER", 5));
        cases.put("꺼짐", () -> disableLink(disabled));
        String expired = token(create(orgAdmin, false, "VIEWER", 5));
        cases.put("만료", () -> jdbc.sql("UPDATE invite_link SET expires_at = :past WHERE id = :id")
                .param("past", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).param("id", linkId(expired))
                .update());
        String used = token(create(orgAdmin, false, "VIEWER", 1));
        assertThat(redeem(newAccount("first@example.com"), used).getStatus()).isEqualTo(200);
        cases.put("상한", () -> { });
        String demoted = token(create(teamAdmin, false, "VIEWER", 5));
        cases.put("만든 사람 권한 상실", () -> jdbc.sql("UPDATE team_membership SET role = 'TEAM_MEMBER' "
                + "WHERE team_id = :team AND account_id = :account").param("team", teamId)
                .param("account", teamAdmin.toString()).update());
        UUID guest = newAccount("nabi@example.com");
        cases.put("손님", () -> insertOrgMembership(guest, "GUEST", "ACTIVE"));
        UUID suspended = newAccount("rest@example.com");
        cases.put("조직 정지", () -> insertOrgMembership(suspended, "MEMBER", "SUSPENDED"));
        UUID teamSuspended = newAccount("pause@example.com");
        cases.put("팀 정지", () -> {
            insertOrgMembership(teamSuspended, "MEMBER", "ACTIVE");
            insertTeamMembership(teamSuspended, "VIEWER", "SUSPENDED");
        });

        for (Map.Entry<String, Runnable> entry : cases.entrySet()) {
            entry.getValue().run();
            String token = switch (entry.getKey()) {
                case "형식 오류" -> "짧은-토큰";
                case "없는 토큰" -> "A".repeat(43);
                case "꺼짐" -> disabled;
                case "만료" -> expired;
                case "상한" -> used;
                case "만든 사람 권한 상실" -> demoted;
                default -> good;
            };
            UUID actor = switch (entry.getKey()) {
                case "손님" -> guest;
                case "조직 정지" -> suspended;
                case "팀 정지" -> teamSuspended;
                default -> outsider;
            };
            long started = System.nanoTime();
            MockHttpServletResponse response = redeem(actor, token);
            durations.add(Duration.ofNanos(System.nanoTime() - started));
            assertThat(response.getStatus()).as(entry.getKey()).isEqualTo(404);
            ObjectNode node = (ObjectNode) jsonMapper.readTree(response.getContentAsString());
            node.remove("traceId");
            bodies.add(node.toString());
        }

        assertThat(bodies).hasSize(cases.size()).allMatch(bodies.get(0)::equals);
        assertThat(bodies.get(0)).doesNotContain("새벽 서재").doesNotContain("개발 팀");
        assertThat(durations).allMatch(duration -> duration.compareTo(FAILURE_DELAY) >= 0);
        assertThat(orgMembership(outsider)).isNull();
        assertThat(useCount(good)).isZero();
        assertThat(useCount(used)).isOne();
    }

    @Test
    @DisplayName("TEN-LINK-06·§14.1: 조직이 링크를 끄거나 팀·조직이 보관되면 이미 만든 링크도 실패")
    void orgAndTeamStateDisableLinks() throws Exception {
        String token = token(create(orgAdmin, false, "VIEWER", 5));

        jdbc.sql("UPDATE organization SET join_link_enabled = :off WHERE id = :org").param("off", false)
                .param("org", orgId).update();
        assertThat(redeem(outsider, token).getStatus()).isEqualTo(404);
        jdbc.sql("UPDATE organization SET join_link_enabled = :on WHERE id = :org").param("on", true)
                .param("org", orgId).update();
        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :team").param("team", teamId).update();
        assertThat(redeem(outsider, token).getStatus()).isEqualTo(404);
        jdbc.sql("UPDATE team SET state = 'ACTIVE' WHERE id = :team").param("team", teamId).update();
        jdbc.sql("UPDATE organization SET state = 'SUSPENDED' WHERE id = :org").param("org", orgId).update();
        assertThat(redeem(outsider, token).getStatus()).isEqualTo(404);
        jdbc.sql("UPDATE organization SET state = 'ACTIVE' WHERE id = :org").param("org", orgId).update();

        assertThat(json(redeem(outsider, token), 200).get("result").asString()).isEqualTo("JOINED");
    }

    @Test
    @DisplayName("TEN-LINK-01·SEC-TOKEN-05·T-TOKEN: 상한 1인 링크를 여러 사람이 동시에 써도 한 사람만 들어감")
    void concurrentUseHonorsLimit() throws Exception {
        String token = token(create(orgAdmin, false, "VIEWER", 1));
        List<UUID> people = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            people.add(newAccount("race" + i + "@example.com"));
        }
        CountDownLatch ready = new CountDownLatch(people.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(people.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (UUID person : people) {
                Callable<Integer> task = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        return -1;
                    }
                    return redeem(person, token).getStatus();
                };
                futures.add(executor.submit(task));
            }
            boolean allReady = ready.await(10, TimeUnit.SECONDS);
            if (allReady) {
                start.countDown();
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(allReady).isTrue();
            assertThat(statuses).containsOnly(200, 404);
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        }
        finally {
            executor.shutdownNow();
        }
        assertThat(useCount(token)).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM team_membership WHERE team_id = :team AND joined_via = 'LINK'")
                .param("team", teamId).query(Long.class).single()).isOne();
    }

    private MockHttpServletResponse create(UUID actor, boolean approvalRequired, String role, int maxUses)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvalRequired", approvalRequired);
        body.put("role", role);
        body.put("maxUses", maxUses);
        return perform(post("/api/teams/" + teamId + "/invite-links")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), actor);
    }

    private String token(MockHttpServletResponse created) throws Exception {
        String url = json(created, 201).get("url").asString();
        return url.substring(url.indexOf('#') + 1);
    }

    private MockHttpServletResponse redeem(UUID actor, String token) throws Exception {
        return perform(post("/api/invite-links/redeem").contentType(MediaType.APPLICATION_JSON).content(body(token)),
                actor);
    }

    private String body(String token) throws Exception {
        return jsonMapper.writeValueAsString(Map.of("token", token));
    }

    private void disableLink(String token) {
        jdbc.sql("UPDATE invite_link SET enabled = :off WHERE id = :id").param("off", false).param("id", linkId(token))
                .update();
    }

    private String linkId(String token) {
        return jdbc.sql("SELECT id FROM invite_link WHERE token_hash = :hash").param("hash", sha256(token))
                .query(String.class).single();
    }

    private int useCount(String token) {
        return jdbc.sql("SELECT use_count FROM invite_link WHERE token_hash = :hash").param("hash", sha256(token))
                .query(Integer.class).single();
    }

    private String orgMembership(UUID account) {
        return jdbc.sql("""
                        SELECT state, kind, joined_via, org_admin FROM org_membership
                        WHERE org_id = :org AND account_id = :account
                        """)
                .param("org", orgId).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + " " + rs.getString("kind") + " "
                        + rs.getString("joined_via") + " " + rs.getBoolean("org_admin"))
                .optional().orElse(null);
    }

    private String teamMembership(UUID account) {
        return jdbc.sql("""
                        SELECT state, role, joined_via, invite_link_id FROM team_membership
                        WHERE team_id = :team AND account_id = :account
                        """)
                .param("team", teamId).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + " " + rs.getString("role") + " "
                        + rs.getString("joined_via") + " " + rs.getString("invite_link_id"))
                .optional().orElse(null);
    }

    private List<String> log(String table, String action, UUID actor) {
        return jdbc.sql("SELECT before_value, after_value FROM " + table
                        + " WHERE action = :action AND actor_id = :actor ORDER BY occurred_at, id")
                .param("action", action).param("actor", actor.toString())
                .query((rs, rowNum) -> rs.getString("before_value") + " " + rs.getString("after_value"))
                .list();
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
                .param("expires", "GUEST".equals(kind) ? now.plusDays(10) : null)
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(UUID account, String role, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, created_at,
                            updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'INVITATION', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", teamId)
                .param("account", account.toString())
                .param("role", role)
                .param("state", state)
                .param("now", now)
                .update();
    }

    private void addTeamMember(UUID account) throws Exception {
        assertThat(perform(post("/api/teams/" + teamId + "/members").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("accountId", account.toString(),
                        "role", "TEAM_MEMBER"))), orgAdmin).getStatus()).isEqualTo(201);
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
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return jsonMapper.readTree(response.getContentAsString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID newAccount(String email) {
        String name = email.substring(0, email.indexOf('@')).toLowerCase();
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(), email, name,
                null, null), "v1", "v1", Instant.now());
    }

}
