package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OperatorTest {

    private static final UUID OPERATOR = UUID.fromString("00000000-0000-7000-8000-00000000a001");
    private static final String REASON = "이용 약관 위반 확인";
    private static final List<String> TABLES = List.of("operator_action_log", "org_activity_log",
            "team_activity_log", "team_membership", "team", "job_type", "org_membership", "organization",
            "api_idempotency_key", "auth_session", "account_consent", "account");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    private UUID admin;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = accounts.createWithConsent(new GoogleProfile("sub-admin-" + UUID.randomUUID(), "admin@example.com",
                "관리자", null, null), "v1", "v1", Instant.now());
        orgId = json(createOrg(), 201).get("orgId").asString();
    }

    @Test
    @DisplayName("TEN-OPS-03·04: 정지는 SUSPENDED와 정지 전 상태·운영 기록·행위자 없는 조직 기록, 해제는 ACTIVE, 반복은 409")
    void suspendsAndReleases() throws Exception {
        MockHttpServletResponse suspended = suspend(OPERATOR, orgId, "  " + REASON + "  ");

        assertThat(suspended.getStatus()).as(suspended.getContentAsString()).isEqualTo(204);
        assertThat(suspended.getContentAsString()).isEmpty();
        assertThat(orgState()).isEqualTo("SUSPENDED:ACTIVE");
        assertThat(operatorLog()).containsExactly(OPERATOR + " ORG_SUSPENDED " + orgId + " " + REASON);
        assertThat(orgLog("ORG_SUSPENDED")).containsExactly("null " + orgId + " ACTIVE SUSPENDED");
        assertThat(perform(get("/api/orgs/" + orgId), admin).getStatus()).isEqualTo(404);
        assertThat(code(suspend(OPERATOR, orgId, REASON), 409)).isEqualTo("ALREADY_PROCESSED");

        MockHttpServletResponse released = unsuspend(OPERATOR, orgId);

        assertThat(released.getStatus()).as(released.getContentAsString()).isEqualTo(204);
        assertThat(released.getContentAsString()).isEmpty();
        assertThat(orgState()).isEqualTo("ACTIVE:ACTIVE");
        assertThat(operatorLog()).containsExactly(OPERATOR + " ORG_SUSPENDED " + orgId + " " + REASON,
                OPERATOR + " ORG_UNSUSPENDED " + orgId + " null");
        assertThat(orgLog("ORG_UNSUSPENDED")).containsExactly("null " + orgId + " SUSPENDED ACTIVE");
        assertThat(perform(get("/api/orgs/" + orgId), admin).getStatus()).isEqualTo(200);
        assertThat(code(unsuspend(OPERATOR, orgId), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(operatorLog()).hasSize(2);
    }

    @Test
    @DisplayName("TEN-OPS-03·04: 보관된 조직도 정지되고, 해제하면 정지 전 상태가 아니라 ACTIVE")
    void releasesArchivedOrgAsActive() throws Exception {
        jdbc.sql("UPDATE organization SET state = 'ARCHIVED' WHERE id = :org").param("org", orgId).update();

        assertThat(suspend(OPERATOR, orgId, REASON).getStatus()).isEqualTo(204);
        assertThat(orgState()).isEqualTo("SUSPENDED:ARCHIVED");
        assertThat(orgLog("ORG_SUSPENDED")).containsExactly("null " + orgId + " ARCHIVED SUSPENDED");

        assertThat(unsuspend(OPERATOR, orgId).getStatus()).isEqualTo(204);
        assertThat(orgState()).isEqualTo("ACTIVE:ARCHIVED");
    }

    @Test
    @DisplayName("API-OPS-02·SEC-ORG-12: 운영자가 아니면 조직 관리자여도 404, 로그인 없으면 401, 최근 인증이 지나면 401")
    void hidesFromNonOperators() throws Exception {
        JsonNode unknownPath = json(perform(post("/api/operator/unknown"), admin), 404);
        for (MockHttpServletResponse response : List.of(suspend(admin, orgId, REASON), unsuspend(admin, orgId),
                suspend(admin, UUID.randomUUID().toString(), REASON), suspend(admin, "not-a-uuid", REASON))) {
            JsonNode body = json(response, 404);
            assertThat(body.get("code").asString()).isEqualTo("NOT_FOUND");
            assertThat(body.get("message")).isEqualTo(unknownPath.get("message"));
            assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(unknownPath.propertyNames());
        }
        assertThat(mockMvc.perform(suspendRequest(orgId, REASON).with(csrfToken())).andReturn().getResponse()
                .getStatus()).isEqualTo(401);
        Instant stale = Instant.now().minus(Duration.ofHours(2));
        assertThat(code(mockMvc.perform(suspendRequest(orgId, REASON)
                        .with(authentication(new AccountAuthentication(OPERATOR, stale))).with(csrfToken()))
                .andReturn().getResponse(), 401)).isEqualTo("REAUTH_REQUIRED");

        assertThat(orgState()).isEqualTo("ACTIVE:null");
        assertThat(operatorLog()).isEmpty();
    }

    @Test
    @DisplayName("TEN-OPS-03·05: 없는 조직·DELETED는 404, 사유가 없거나 길거나 제어 문자·이메일·주소·토큰 모양이면 400")
    void rejectsBadTargetsAndReasons() throws Exception {
        assertThat(suspend(OPERATOR, UUID.randomUUID().toString(), REASON).getStatus()).isEqualTo(404);
        assertThat(unsuspend(OPERATOR, UUID.randomUUID().toString()).getStatus()).isEqualTo(404);
        assertThat(suspend(OPERATOR, "not-a-uuid", REASON).getStatus()).isEqualTo(400);

        Map<String, String> rejected = new LinkedHashMap<>();
        rejected.put("   ", "REQUIRED");
        rejected.put("가".repeat(201), "TOO_LONG");
        rejected.put("첫 줄\n둘째 줄", "INVALID_FORMAT");
        rejected.put("신고자 reporter@example.com", "NOT_ALLOWED_VALUE");
        rejected.put("근거 https://example.com/report", "NOT_ALLOWED_VALUE");
        rejected.put("토큰 abcdefghijklmnopqrstuvwxyz0123456789", "NOT_ALLOWED_VALUE");
        for (Map.Entry<String, String> entry : rejected.entrySet()) {
            assertThat(fields(suspend(OPERATOR, orgId, entry.getKey()))).as(entry.getValue())
                    .containsExactly("reason:" + entry.getValue());
        }
        assertThat(fields(perform(post(path(orgId, "suspend")).contentType(MediaType.APPLICATION_JSON)
                .content("{}"), OPERATOR))).containsExactly("reason:REQUIRED");
        assertThat(suspend(OPERATOR, orgId, "가".repeat(200)).getStatus()).isEqualTo(204);

        jdbc.sql("UPDATE organization SET state = 'DELETED' WHERE id = :org").param("org", orgId).update();
        assertThat(suspend(OPERATOR, orgId, REASON).getStatus()).isEqualTo(404);
        assertThat(unsuspend(OPERATOR, orgId).getStatus()).isEqualTo(404);
        assertThat(operatorLog()).hasSize(1);
    }

    @Test
    @DisplayName("TEN-OPS-04: 활동 중인 조직 관리자가 없으면 해제는 409 LAST_ADMIN_REQUIRED이고 정지 유지")
    void keepsSuspendedWithoutAdmin() throws Exception {
        assertThat(suspend(OPERATOR, orgId, REASON).getStatus()).isEqualTo(204);
        jdbc.sql("UPDATE org_membership SET state = 'LEFT', org_admin = :admin WHERE org_id = :org")
                .param("admin", false).param("org", orgId).update();

        assertThat(code(unsuspend(OPERATOR, orgId), 409)).isEqualTo("LAST_ADMIN_REQUIRED");

        assertThat(orgState()).isEqualTo("SUSPENDED:ACTIVE");
        assertThat(operatorLog()).hasSize(1);
        assertThat(orgLog("ORG_UNSUSPENDED")).isEmpty();
    }

    @Test
    @DisplayName("TEN-OPS-03: 같은 조직을 동시에 정지해도 한 번만 처리되고 기록도 한 줄")
    void suspendsOnceUnderConcurrency() throws Exception {
        int callers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return suspend(OPERATOR, orgId, REASON).getStatus();
                }
                catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<Integer> call : calls) {
            statuses.add(call.get(30, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(204, 409, 409, 409);
        assertThat(operatorLog()).hasSize(1);
        assertThat(orgLog("ORG_SUSPENDED")).hasSize(1);
    }

    private MockHttpServletResponse suspend(UUID actor, String org, String reason) throws Exception {
        return perform(suspendRequest(org, reason), actor);
    }

    private MockHttpServletRequestBuilder suspendRequest(String org, String reason) {
        return post(path(org, "suspend")).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("reason", reason)));
    }

    private MockHttpServletResponse unsuspend(UUID actor, String org) throws Exception {
        return perform(post(path(org, "unsuspend")), actor);
    }

    private static String path(String org, String action) {
        return "/api/operator/orgs/" + org + "/" + action;
    }

    private MockHttpServletResponse createOrg() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "새벽 서재");
        body.put("visibility", "PRIVATE");
        body.put("timeZone", "Asia/Seoul");
        body.put("firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"));
        return perform(post("/api/orgs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), admin);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private String orgState() {
        return jdbc.sql("SELECT state, state_before_suspend FROM organization WHERE id = :org")
                .param("org", orgId)
                .query((rs, rowNum) -> rs.getString("state") + ":" + rs.getString("state_before_suspend"))
                .single();
    }

    private List<String> operatorLog() {
        return jdbc.sql("""
                        SELECT operator_account_id, action, org_id, reason FROM operator_action_log
                        ORDER BY occurred_at, id
                        """)
                .query((rs, rowNum) -> rs.getString("operator_account_id") + " " + rs.getString("action") + " "
                        + rs.getString("org_id") + " " + rs.getString("reason"))
                .list();
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

}
