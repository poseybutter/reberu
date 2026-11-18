package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
class InviteLinkTest {

    static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "invite_link", "invitation",
            "team_custom_field", "team_task_label", "team_membership", "team", "job_type", "org_membership",
            "organization", "api_idempotency_key", "account_consent", "account");

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
    private String otherTeamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        orgAdmin = newAccount("orgadmin@example.com");
        teamAdmin = newAccount("teamadmin@example.com");
        member = newAccount("haneul@example.com");
        outsider = newAccount("outsider@example.com");
        orgId = json(createOrg(orgAdmin, "새벽 서재"), 201).get("orgId").asString();
        insertOrgMembership(teamAdmin);
        insertOrgMembership(member);
        teamId = createTeam("개발 팀", teamAdmin);
        otherTeamId = createTeam("디자인 팀", orgAdmin);
        addTeamMember(teamId, member);
        allowLinks(true);
    }

    @Test
    @DisplayName("SEC-TOKEN-01·02·TEN-LINK-04: 토큰은 32바이트 base64url, 주소에만 한 번 담기고 DB에는 SHA-256 해시만 저장")
    void createsLinkWithHashedToken() throws Exception {
        JsonNode created = json(create(teamAdmin, teamId, link(false, "TEAM_MEMBER", null, 5)), 201);

        String url = created.get("url").asString();
        assertThat(url).startsWith("http://localhost:8080/join#");
        String token = url.substring(url.indexOf('#') + 1);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        String linkId = created.get("linkId").asString();
        Map<String, Object> row = jdbc.sql("SELECT * FROM invite_link WHERE id = :id").param("id", linkId)
                .query().singleRow();
        assertThat(row.get("token_hash")).isEqualTo(sha256(token));
        assertThat(row.values()).noneMatch(value -> value != null && value.toString().contains(token));
        assertThat(row.get("use_count")).hasToString("0");
        assertThat(row.get("max_uses")).hasToString("5");
        assertThat(row.get("role")).isEqualTo("TEAM_MEMBER");
        Instant expiresAt = Instant.parse(created.get("expiresAt").asString());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofDays(7).minusMinutes(1),
                Duration.ofDays(7));
        assertThat(teamLog(teamId, "INVITE_LINK_CREATED")).containsExactly(teamAdmin + " " + linkId + " TEAM_MEMBER");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM api_idempotency_key WHERE response_body LIKE :token")
                .param("token", "%" + token + "%").query(Long.class).single()).isZero();

        String second = json(create(teamAdmin, teamId, link(true, "VIEWER", 30, 1)), 201).get("url").asString();
        assertThat(second).isNotEqualTo(url);
    }

    @Test
    @DisplayName("TEN-LINK-02·03·SEC-TOKEN-04·07: 승인 여부·역할·기한·횟수가 규칙에 맞지 않으면 400, 링크를 만들지 않음")
    void rejectsInvalidInput() throws Exception {
        assertThat(fields(create(teamAdmin, teamId, link(null, null, null, null))))
                .containsExactly("approvalRequired:REQUIRED", "role:REQUIRED", "maxUses:REQUIRED");
        assertThat(fields(create(teamAdmin, teamId, link(false, "TEAM_ADMIN", null, 1))))
                .containsExactly("role:NOT_ALLOWED_VALUE");
        for (int days : new int[] {0, 31}) {
            assertThat(fields(create(teamAdmin, teamId, link(false, "VIEWER", days, 1)))).as("" + days)
                    .containsExactly("expiresInDays:NOT_ALLOWED_VALUE");
        }
        for (int uses : new int[] {0, -1, 1001}) {
            assertThat(fields(create(teamAdmin, teamId, link(false, "VIEWER", null, uses)))).as("" + uses)
                    .containsExactly("maxUses:NOT_ALLOWED_VALUE");
        }
        assertThat(count()).isZero();

        Instant expiresAt = Instant.parse(json(create(teamAdmin, teamId, link(false, "VIEWER", 30, 1000)), 201)
                .get("expiresAt").asString());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofDays(30).minusMinutes(1),
                Duration.ofDays(30));
    }

    @Test
    @DisplayName("TEN-JOIN-01: 조직이 초대 링크를 끄면 목록·만들기·끄기 모두 404")
    void linksOffReturnsNotFound() throws Exception {
        String linkId = linkId(create(teamAdmin, teamId, link(false, "VIEWER", null, 3)));
        allowLinks(false);

        assertThat(code(perform(get(linksPath(teamId)), teamAdmin), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(create(teamAdmin, teamId, link(false, "VIEWER", null, 3)), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(disable(teamAdmin, teamId, linkId), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(create(orgAdmin, teamId, link(false, "VIEWER", null, 3)), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(create(teamAdmin, teamId, link(null, "TEAM_ADMIN", 99, 0)), 404)).isEqualTo("NOT_FOUND");
        assertThat(count()).isOne();
        assertThat(enabled(linkId)).isTrue();
    }

    @Test
    @DisplayName("TEN-PERM-01: 링크 관리는 그 팀의 팀 관리자와 조직 관리자만, 팀원은 403, 다른 팀·조직 밖은 404")
    void onlyAdminsManageLinks() throws Exception {
        assertThat(create(orgAdmin, teamId, link(false, "VIEWER", null, 3)).getStatus()).isEqualTo(201);
        String linkId = linkId(create(teamAdmin, teamId, link(false, "VIEWER", null, 3)));

        assertThat(code(perform(get(linksPath(teamId)), member), 403)).isEqualTo("FORBIDDEN_ACTION");
        assertThat(code(create(member, teamId, link(false, "VIEWER", null, 3)), 403)).isEqualTo("FORBIDDEN_ACTION");
        assertThat(code(disable(member, teamId, linkId), 403)).isEqualTo("FORBIDDEN_ACTION");
        assertThat(code(perform(get(linksPath(teamId)), outsider), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(create(outsider, teamId, link(false, "VIEWER", null, 3)), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(disable(teamAdmin, otherTeamId, linkId), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(disable(orgAdmin, otherTeamId, linkId), 404)).isEqualTo("NOT_FOUND");
        assertThat(count()).isEqualTo(2);
        assertThat(enabled(linkId)).isTrue();
    }

    @Test
    @DisplayName("API-CON-05·TEN-LINK-08: 같은 중복 방지 키의 두 번째 만들기는 409, 토큰 재전송 없음, 조직이 링크를 끈 뒤에는 같은 키도 404")
    void sameKeyIsNotReplayed() throws Exception {
        String key = UUID.randomUUID().toString();
        MockHttpServletResponse first = create(teamAdmin, teamId, link(false, "VIEWER", null, 3), key);
        MockHttpServletResponse second = create(teamAdmin, teamId, link(false, "VIEWER", null, 3), key);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(code(second, 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(second.getContentAsString()).doesNotContain("/join#");
        assertThat(count()).isOne();

        allowLinks(false);
        assertThat(code(create(teamAdmin, teamId, link(false, "VIEWER", null, 3), key), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(create(teamAdmin, teamId, link(false, "VIEWER", null, 9), key), 404)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("TEN-LINK-04: 목록은 최신부터 커서 페이지, 토큰·해시 없이 설정·사용 횟수·만든 사람")
    void listsLinksWithoutToken() throws Exception {
        String older = linkId(create(teamAdmin, teamId, link(true, "VIEWER", 3, 2)));
        String newer = linkId(create(orgAdmin, teamId, link(false, "TEAM_MEMBER", null, 9)));
        create(orgAdmin, otherTeamId, link(false, "VIEWER", null, 1));
        disable(teamAdmin, teamId, older);

        MockHttpServletResponse response = perform(get(linksPath(teamId)), teamAdmin);
        JsonNode items = json(response, 200).get("items");

        assertThat(values(items, "linkId")).containsExactly(newer, older);
        assertThat(response.getContentAsString()).doesNotContain("token").doesNotContain("/join#");
        JsonNode first = items.get(0);
        assertThat(first.get("approvalRequired").asBoolean()).isFalse();
        assertThat(first.get("role").asString()).isEqualTo("TEAM_MEMBER");
        assertThat(first.get("useCount").asInt()).isZero();
        assertThat(first.get("maxUses").asInt()).isEqualTo(9);
        assertThat(first.get("enabled").asBoolean()).isTrue();
        assertThat(first.get("createdBy").asString()).isEqualTo(orgAdmin.toString());
        assertThat(first.get("createdByName").asString()).isEqualTo("orgadmin");
        assertThat(items.get(1).get("approvalRequired").asBoolean()).isTrue();
        assertThat(items.get(1).get("enabled").asBoolean()).isFalse();

        JsonNode page = json(perform(get(linksPath(teamId) + "?limit=1"), teamAdmin), 200);
        assertThat(values(page.get("items"), "linkId")).containsExactly(newer);
        JsonNode next = json(perform(get(linksPath(teamId) + "?limit=1&cursor="
                + page.get("nextCursor").asString()), teamAdmin), 200);
        assertThat(values(next.get("items"), "linkId")).containsExactly(older);
        assertThat(next.get("nextCursor").isNull()).isTrue();
        assertThat(code(perform(get(linksPath(otherTeamId) + "?limit=1&cursor="
                + page.get("nextCursor").asString()), orgAdmin), 400)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("SEC-TOKEN-04: 끄기는 한 번만, 두 번째는 409, 다시 켜는 길 없음")
    void disablesLink() throws Exception {
        String linkId = linkId(create(teamAdmin, teamId, link(false, "VIEWER", null, 3)));

        assertThat(disable(orgAdmin, teamId, linkId).getStatus()).isEqualTo(204);
        assertThat(enabled(linkId)).isFalse();
        assertThat(code(disable(teamAdmin, teamId, linkId), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(code(disable(teamAdmin, teamId, UUID.randomUUID().toString()), 404)).isEqualTo("NOT_FOUND");
        assertThat(teamLog(teamId, "INVITE_LINK_DISABLED"))
                .containsExactly(orgAdmin + " " + linkId + " ENABLED DISABLED");
    }

    @Test
    @DisplayName("§14.1: 팀 보관은 그 팀 링크를 끄고, 복원해도 꺼진 채로 둠")
    void archivingTeamDisablesLinks() throws Exception {
        String first = linkId(create(teamAdmin, teamId, link(false, "VIEWER", null, 3)));
        String second = linkId(create(teamAdmin, teamId, link(true, "VIEWER", null, 3)));
        String other = linkId(create(orgAdmin, otherTeamId, link(false, "VIEWER", null, 3)));
        disable(teamAdmin, teamId, second);

        assertThat(perform(post(teamPath(teamId) + "/archive"), orgAdmin).getStatus()).isEqualTo(204);
        assertThat(enabled(first)).isFalse();
        assertThat(enabled(other)).isTrue();
        assertThat(teamLog(teamId, "INVITE_LINK_DISABLED")).containsExactly(
                teamAdmin + " " + second + " ENABLED DISABLED", orgAdmin + " " + first + " ENABLED DISABLED");
        assertThat(code(create(teamAdmin, teamId, link(false, "VIEWER", null, 3)), 409)).isEqualTo("READ_ONLY");

        assertThat(perform(post(teamPath(teamId) + "/restore"), orgAdmin).getStatus()).isEqualTo(204);
        assertThat(enabled(first)).isFalse();
    }

    @Test
    @DisplayName("§14.1: 조직 보관은 조직의 모든 링크를 끔")
    void archivingOrgDisablesLinks() throws Exception {
        String first = linkId(create(teamAdmin, teamId, link(false, "VIEWER", null, 3)));
        String other = linkId(create(orgAdmin, otherTeamId, link(false, "VIEWER", null, 3)));

        assertThat(perform(post("/api/orgs/" + orgId + "/archive"), orgAdmin).getStatus()).isEqualTo(204);
        assertThat(enabled(first)).isFalse();
        assertThat(enabled(other)).isFalse();
        assertThat(teamLog(otherTeamId, "INVITE_LINK_DISABLED"))
                .containsExactly(orgAdmin + " " + other + " ENABLED DISABLED");
        assertThat(code(create(orgAdmin, teamId, link(false, "VIEWER", null, 3)), 409)).isEqualTo("READ_ONLY");
    }

    static Map<String, Object> link(Boolean approvalRequired, String role, Integer expiresInDays, Integer maxUses) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvalRequired", approvalRequired);
        body.put("role", role);
        body.put("expiresInDays", expiresInDays);
        body.put("maxUses", maxUses);
        return body;
    }

    private MockHttpServletResponse create(UUID actor, String team, Map<String, Object> body) throws Exception {
        return create(actor, team, body, UUID.randomUUID().toString());
    }

    private MockHttpServletResponse create(UUID actor, String team, Map<String, Object> body, String key)
            throws Exception {
        return perform(post(linksPath(team)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), actor);
    }

    private MockHttpServletResponse disable(UUID actor, String team, String linkId) throws Exception {
        return perform(post(linksPath(team) + "/" + linkId + "/disable"), actor);
    }

    private static String linksPath(String team) {
        return "/api/teams/" + team + "/invite-links";
    }

    private String teamPath(String team) {
        return "/api/orgs/" + orgId + "/teams/" + team;
    }

    private String linkId(MockHttpServletResponse response) throws Exception {
        return json(response, 201).get("linkId").asString();
    }

    private boolean enabled(String linkId) {
        return jdbc.sql("SELECT enabled FROM invite_link WHERE id = :id").param("id", linkId).query(Boolean.class)
                .single();
    }

    private long count() {
        return jdbc.sql("SELECT COUNT(*) FROM invite_link").query(Long.class).single();
    }

    private void allowLinks(boolean allowed) {
        jdbc.sql("UPDATE organization SET join_link_enabled = :allowed WHERE id = :org")
                .param("allowed", allowed).param("org", orgId).update();
    }

    private List<String> teamLog(String team, String action) {
        return jdbc.sql("""
                        SELECT actor_id, target_id, before_value, after_value FROM team_activity_log
                        WHERE team_id = :team AND action = :action ORDER BY occurred_at, id
                        """)
                .param("team", team).param("action", action)
                .query((rs, rowNum) -> rs.getString("actor_id") + " " + rs.getString("target_id")
                        + (rs.getString("before_value") == null ? "" : " " + rs.getString("before_value")) + " "
                        + rs.getString("after_value"))
                .list();
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

    private void insertOrgMembership(UUID account) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            created_at, updated_at, version)
                        VALUES (:id, :org, :account, 'MEMBER', :admin, 'ACTIVE', 'INVITATION', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", account.toString())
                .param("admin", false)
                .param("now", now)
                .update();
    }

    private void addTeamMember(String team, UUID account) throws Exception {
        assertThat(perform(post("/api/teams/" + team + "/members").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("accountId", account.toString(),
                        "role", "TEAM_MEMBER"))), orgAdmin).getStatus()).isEqualTo(201);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private static List<String> values(JsonNode items, String field) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.get(field).asString()));
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

    private UUID newAccount(String email) {
        String name = email.substring(0, email.indexOf('@')).toLowerCase();
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(), email, name,
                null, null), "v1", "v1", Instant.now());
    }

}
