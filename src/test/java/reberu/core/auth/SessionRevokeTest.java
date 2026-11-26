package reberu.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.FakeGoogle.IdToken;
import reberu.core.auth.security.AccountAuthentication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionRevokeTest {

    private static final FakeGoogle GOOGLE = new FakeGoogle();
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-7000-8000-00000000a001");
    private static final String SUB = "sub-revoked";
    private static final String EMAIL = "member@example.com";
    private static final List<String> TABLES = List.of("operator_action_log", "org_activity_log",
            "team_activity_log", "team_membership", "team", "job_type", "org_membership", "organization",
            "api_idempotency_key", "auth_pending_signup", "auth_session", "account_consent", "account");

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    private TestBrowser browser;
    private UUID admin;
    private UUID member;
    private String orgId;
    private String teamId;

    @DynamicPropertySource
    static void googleEndpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.google.authorization-uri",
                () -> GOOGLE.baseUrl() + "/auth");
        registry.add("spring.security.oauth2.client.provider.google.token-uri", () -> GOOGLE.baseUrl() + "/token");
        registry.add("spring.security.oauth2.client.provider.google.jwk-set-uri", () -> GOOGLE.baseUrl() + "/jwks");
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = accounts.createWithConsent(new GoogleProfile("sub-admin-" + UUID.randomUUID(), "admin@example.com",
                "관리자", null, null), "v1", "v1", Instant.now());
        member = accounts.createWithConsent(new GoogleProfile(SUB, EMAIL, "구성원", null, null), "v1", "v1",
                Instant.now());
        JsonNode created = createOrg();
        orgId = created.get("orgId").asString();
        teamId = created.get("teamId").asString();
        insertOrgMembership();
        insertTeamMembership();
        browser = new TestBrowser(port);
        signIn();
        assertThat(orgStatus()).isEqualTo(200);
        assertThat(teamStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("T-SESS-REVOKE: 운영자가 조직을 정지하면 기존 세션의 다음 요청부터 404, 해제하면 다시 열림, 세션은 유지")
    void orgSuspensionBlocksNextRequest() throws Exception {
        assertThat(operate("suspend", "{\"reason\":\"이용 약관 위반 확인\"}")).isEqualTo(204);

        assertThat(orgStatus()).isEqualTo(404);
        assertThat(teamStatus()).isEqualTo(404);
        assertThat(sessionState()).isEqualTo("AUTHENTICATED");
        assertThat(sessionCount()).isOne();

        assertThat(operate("unsuspend", null)).isEqualTo(204);

        assertThat(orgStatus()).isEqualTo(200);
        assertThat(teamStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("T-SESS-REVOKE: 조직 멤버십 정지·내보내기 직후 기존 세션의 조직·팀 요청은 404, 해제하면 다시 열림")
    void orgMembershipSuspensionAndRemovalBlockNextRequest() throws Exception {
        assertThat(asAdmin("/api/orgs/" + orgId + "/members/" + member + "/suspend")).isEqualTo(204);
        assertThat(orgStatus()).isEqualTo(404);
        assertThat(teamStatus()).isEqualTo(404);

        assertThat(asAdmin("/api/orgs/" + orgId + "/members/" + member + "/unsuspend")).isEqualTo(204);
        assertThat(orgStatus()).isEqualTo(200);
        assertThat(teamStatus()).isEqualTo(200);

        assertThat(asAdmin("/api/orgs/" + orgId + "/members/" + member + "/remove")).isEqualTo(204);
        assertThat(orgStatus()).isEqualTo(404);
        assertThat(teamStatus()).isEqualTo(404);
        assertThat(sessionState()).isEqualTo("AUTHENTICATED");
    }

    @Test
    @DisplayName("T-SESS-REVOKE: 팀 멤버십 정지·내보내기 직후 기존 세션의 그 팀 요청은 404, 조직 요청은 그대로")
    void teamMembershipEndBlocksNextRequest() throws Exception {
        assertThat(asAdmin("/api/teams/" + teamId + "/members/" + member + "/suspend")).isEqualTo(204);
        assertThat(teamStatus()).isEqualTo(404);
        assertThat(orgStatus()).isEqualTo(200);

        assertThat(asAdmin("/api/teams/" + teamId + "/members/" + member + "/unsuspend")).isEqualTo(204);
        assertThat(teamStatus()).isEqualTo(200);

        assertThat(asAdmin("/api/teams/" + teamId + "/members/" + member + "/remove")).isEqualTo(204);
        assertThat(teamStatus()).isEqualTo(404);
        assertThat(orgStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("T-SESS-REVOKE: 스스로 팀·조직을 나간 직후 같은 세션의 요청은 404")
    void leavingBlocksNextRequest() {
        assertThat(browser.send("POST", "/api/me/teams/" + teamId + "/leave", null).statusCode()).isEqualTo(204);
        assertThat(teamStatus()).isEqualTo(404);
        assertThat(orgStatus()).isEqualTo(200);

        assertThat(browser.send("POST", "/api/me/orgs/" + orgId + "/leave", null).statusCode()).isEqualTo(204);
        assertThat(orgStatus()).isEqualTo(404);
        assertThat(sessionState()).isEqualTo("AUTHENTICATED");
    }

    @Test
    @DisplayName("T-SESS-REVOKE: 손님 이용 기한이 지나면 만료 작업 전이라도 기존 세션의 요청은 404")
    void guestExpiryBlocksNextRequest() {
        LocalDateTime future = LocalDateTime.now(ZoneOffset.UTC).plusDays(1);
        setGuestExpiry(future);
        assertThat(orgStatus()).isEqualTo(200);
        assertThat(teamStatus()).isEqualTo(200);

        setGuestExpiry(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        assertThat(orgStatus()).isEqualTo(404);
        assertThat(teamStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("T-SESS-REVOKE: 계정이 정지되면 기존 세션의 다음 요청은 401이고 세션이 지워짐")
    void accountSuspensionEndsSession() {
        jdbc.sql("UPDATE account SET state = 'SUSPENDED' WHERE id = :id").param("id", member.toString()).update();

        HttpResponse<String> response = browser.get("/api/orgs/" + orgId);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(jsonMapper.readTree(response.body()).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(sessionCount()).isZero();
        assertThat(sessionState()).isEqualTo("ANONYMOUS");
    }

    private int orgStatus() {
        return browser.get("/api/orgs/" + orgId).statusCode();
    }

    private int teamStatus() {
        return browser.get("/api/teams/" + teamId).statusCode();
    }

    private String sessionState() {
        HttpResponse<String> response = browser.get("/api/auth/session");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return jsonMapper.readTree(response.body()).get("state").asString();
    }

    private int sessionCount() {
        return jdbc.sql("SELECT COUNT(*) FROM auth_session WHERE account_id = :account")
                .param("account", member.toString()).query(Integer.class).single();
    }

    private int operate(String action, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/operator/orgs/" + orgId + "/" + action);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return perform(request, OPERATOR).getStatus();
    }

    private int asAdmin(String path) throws Exception {
        return perform(post(path), admin).getStatus();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private JsonNode createOrg() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "새벽 서재");
        body.put("visibility", "PRIVATE");
        body.put("timeZone", "Asia/Seoul");
        body.put("firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"));
        MockHttpServletResponse response = perform(post("/api/orgs")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), admin);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return jsonMapper.readTree(response.getContentAsString());
    }

    private void signIn() {
        HttpResponse<String> response = browser.get("/oauth2/authorization/google");
        assertThat(response.statusCode()).isEqualTo(302);
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        Map<String, String> query = new HashMap<>();
        for (String pair : location.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            query.put(pair.substring(0, separator),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        String code = GOOGLE.issue(query.get("code_challenge"), IdToken.valid(SUB, EMAIL, query.get("nonce")));
        assertThat(browser.get("/login/oauth2/code/google?code=" + code + "&state="
                + URLEncoder.encode(query.get("state"), StandardCharsets.UTF_8)).statusCode()).isEqualTo(302);
        assertThat(sessionState()).isEqualTo("AUTHENTICATED");
    }

    private void setGuestExpiry(LocalDateTime expiresAt) {
        jdbc.sql("UPDATE org_membership SET kind = 'GUEST', expires_at = :expires WHERE account_id = :account")
                .param("expires", expiresAt).param("account", member.toString()).update();
        jdbc.sql("UPDATE team_membership SET expires_at = :expires WHERE account_id = :account")
                .param("expires", expiresAt).param("account", member.toString()).update();
    }

    private void insertOrgMembership() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            created_at, updated_at, version, admission_review)
                        VALUES (:id, :org, :account, 'MEMBER', FALSE, 'ACTIVE', 'INVITATION', :now, :now, 0, FALSE)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", member.toString())
                .param("now", now)
                .update();
    }

    private void insertTeamMembership() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, created_at,
                            updated_at, version)
                        VALUES (:id, :team, :account, 'TEAM_MEMBER', 'ACTIVE', 'DIRECT', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", teamId)
                .param("account", member.toString())
                .param("now", now)
                .update();
    }

}
