package reberu.core.support.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import reberu.core.support.RateLimit;
import reberu.core.support.SecurityMetrics;
import reberu.core.support.SecurityMetrics.Rejection;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
class ObservabilityTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_]+)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> FRAMEWORK_URIS = Set.of("UNKNOWN", "NOT_FOUND", "REDIRECTION", "root", "/**");
    private static final String EMAIL = "metrics-admin@example.com";
    private static final String TOKEN = "invite-token-0123456789abcdefghij";
    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "team_membership",
            "team", "job_type", "org_membership", "organization", "api_idempotency_key", "auth_session",
            "account_consent", "account");

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Value("${management.server.address}")
    String managementAddress;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MeterRegistry registry;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private UUID admin;

    @BeforeEach
    void setUp() {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = accounts.createWithConsent(new GoogleProfile("sub-metrics-" + UUID.randomUUID(), EMAIL, "관리자",
                null, null), "v1", "v1", Instant.now());
    }

    @Test
    @DisplayName("OPS-OBS-01: 지표 끝점은 루프백 관리 포트에만, 관리 포트에는 지표와 상태 확인뿐")
    void metricsAreServedOnlyOnTheManagementPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        assertThat(managementAddress).isEqualTo("127.0.0.1");
        for (String path : List.of("/actuator", "/actuator/prometheus", "/actuator/health", "/actuator/metrics",
                "/actuator/env")) {
            HttpResponse<String> response = app(path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.body()).as(path).doesNotContain("jvm_", "_links", "\"status\"");
        }

        HttpResponse<String> scrape = management("/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "hikaricp_connections_active",
                "hikaricp_connections_pending", "http_server_requests_seconds_bucket", "reberu_events_incomplete ",
                "reberu_events_incomplete_oldest_age_seconds ", "reberu_security_rejections_total",
                "reberu_security_rate_limited_total");
        assertThat(management("/actuator/health").statusCode()).isEqualTo(200);
        for (String path : List.of("/actuator/env", "/actuator/metrics", "/actuator/beans", "/actuator/loggers",
                "/actuator/heapdump", "/actuator/configprops", "/actuator/mappings", "/actuator/info")) {
            assertThat(management(path).statusCode()).as(path).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("OPS-OBS-02: 앱 포트의 상태 확인은 로그인 없이 상태 값만")
    void appPortExposesOnlyBareHealthProbes() throws Exception {
        for (String path : List.of("/livez", "/readyz")) {
            HttpResponse<String> response = app(path);

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("{\"status\":\"UP\"}");
        }
        assertThat(management("/actuator/health").body()).doesNotContain("components", "details", "db", "diskSpace");
    }

    @Test
    @DisplayName("OPS-OBS-03: 지표 출력에 계정·조직·팀 ID, 이메일, 토큰, 요청 주소 원문 없이 경로 틀만")
    void metricsCarryNoIdentifiers() throws Exception {
        String orgId = createOrg();
        String teamId = jdbc.sql("SELECT id FROM team WHERE org_id = :org").param("org", orgId)
                .query(String.class).single();
        UUID unknown = UUID.randomUUID();
        perform(get("/api/orgs/" + orgId), admin);
        perform(get("/api/teams/" + teamId), admin);
        perform(get("/api/teams/" + unknown), admin);
        perform(get("/api/orgs/" + orgId + "/members").queryParam("q", EMAIL), admin);
        app("/api/invite-links/" + TOKEN + "?token=" + TOKEN + "&email=" + EMAIL);
        app("/unknown/" + unknown + "?email=" + EMAIL);

        String scrape = management("/actuator/prometheus").body();

        assertThat(scrape).contains("uri=\"/api/orgs/{orgId}\"", "uri=\"/api/teams/{teamId}\"");
        assertThat(scrape).doesNotContain(orgId, teamId, unknown.toString(), admin.toString(), EMAIL, TOKEN, "@",
                "?");
        assertThat(UUID_SHAPE.matcher(scrape).find()).isFalse();
    }

    @Test
    @DisplayName("OPS-OBS-04·07·08: 이름표 값은 경로 틀·실패 종류·규칙 ID·구독 메서드 같은 고정 목록뿐")
    void labelValuesComeFromFixedLists() throws Exception {
        String orgId = createOrg();
        perform(get("/api/orgs/" + orgId), admin);
        perform(get("/api/orgs/" + UUID.randomUUID()), admin);
        app("/unknown/" + UUID.randomUUID());
        app("/livez");
        Set<String> uris = new HashSet<>(FRAMEWORK_URIS);
        uris.addAll(List.of("/livez", "/readyz"));
        handlerMapping.getHandlerMethods().keySet().forEach(info -> uris.addAll(info.getPatternValues()));

        Map<String, Set<String>> labels = labels(management("/actuator/prometheus").body());

        assertThat(labels.get("uri")).isNotEmpty().isSubsetOf(uris);
        assertThat(labels.get("kind")).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(Rejection.values()).map(Rejection::tag).toList());
        assertThat(labels.get("rule")).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(RateLimit.values()).map(RateLimit::id).toList());
        assertThat(labels.getOrDefault("listener", Set.of())).allMatch(value -> value.matches("\\w+\\.\\w+"));
        assertThat(labels.get("exception")).allMatch(value -> value.matches("\\w+"));
        assertThat(labels.keySet()).doesNotContain("account", "accountId", "org", "orgId", "team", "teamId",
                "email", "token", "message", "query", "path");
    }

    @Test
    @DisplayName("OPS-OBS-08: CSRF 실패와 권한 거부는 종류별로만 집계")
    void countsRejectionsByKind() throws Exception {
        String orgId = createOrg();
        UUID member = accounts.createWithConsent(new GoogleProfile("sub-metrics-" + UUID.randomUUID(),
                "metrics-member@example.com", "구성원", null, null), "v1", "v1", Instant.now());
        insertOrgMembership(orgId, member);
        UUID outsider = accounts.createWithConsent(new GoogleProfile("sub-metrics-" + UUID.randomUUID(),
                "metrics-outsider@example.com", "바깥 사람", null, null), "v1", "v1", Instant.now());
        double csrf = count(Rejection.CSRF_INVALID);
        double hidden = count(Rejection.ACCESS_HIDDEN);
        double forbidden = count(Rejection.ACCESS_FORBIDDEN);

        MockHttpServletResponse withoutToken = mockMvc.perform(post("/api/orgs")
                .with(authentication(new AccountAuthentication(admin, Instant.now())))).andReturn().getResponse();
        MockHttpServletResponse otherOrg = perform(get("/api/orgs/" + orgId), outsider);
        MockHttpServletResponse adminOnly = perform(get("/api/orgs/" + orgId + "/invitations"), member);
        MockHttpServletResponse allowed = perform(get("/api/orgs/" + orgId), member);

        assertThat(withoutToken.getStatus()).isEqualTo(403);
        assertThat(otherOrg.getStatus()).isEqualTo(404);
        assertThat(adminOnly.getStatus()).as(adminOnly.getContentAsString()).isEqualTo(403);
        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(count(Rejection.CSRF_INVALID) - csrf).isEqualTo(1);
        assertThat(count(Rejection.ACCESS_HIDDEN) - hidden).isEqualTo(1);
        assertThat(count(Rejection.ACCESS_FORBIDDEN) - forbidden).isEqualTo(1);
    }

    @Test
    @DisplayName("OPS-OBS-08: 없는 조직과 닫힌 조직의 404는 권한 거부로 집계하지 않음")
    void skipsMissingAndClosedOrgs() throws Exception {
        String orgId = createOrg();
        double hidden = count(Rejection.ACCESS_HIDDEN);

        MockHttpServletResponse missing = perform(get("/api/orgs/" + UUID.randomUUID()), admin);
        MockHttpServletResponse malformed = perform(get("/api/orgs/not-an-id"), admin);
        jdbc.sql("UPDATE organization SET state = 'SUSPENDED' WHERE id = :org").param("org", orgId).update();
        MockHttpServletResponse closed = perform(get("/api/orgs/" + orgId), admin);

        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(malformed.getStatus()).isEqualTo(404);
        assertThat(closed.getStatus()).isEqualTo(404);
        assertThat(count(Rejection.ACCESS_HIDDEN) - hidden).isZero();
    }

    private double count(Rejection kind) {
        return registry.counter(SecurityMetrics.REJECTIONS, SecurityMetrics.KIND, kind.tag()).count();
    }

    private static Map<String, Set<String>> labels(String scrape) {
        Map<String, Set<String>> labels = new LinkedHashMap<>();
        for (String line : scrape.lines().filter(line -> !line.startsWith("#")).collect(Collectors.toList())) {
            Matcher matcher = LABEL.matcher(line);
            while (matcher.find()) {
                labels.computeIfAbsent(matcher.group(1), key -> new TreeSet<>()).add(matcher.group(2));
            }
        }
        return labels;
    }

    private String createOrg() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "새벽 서재");
        body.put("visibility", "PRIVATE");
        body.put("timeZone", "Asia/Seoul");
        body.put("firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"));
        MockHttpServletResponse created = perform(post("/api/orgs")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), admin);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return jsonMapper.readTree(created.getContentAsString()).get("orgId").asString();
    }

    private void insertOrgMembership(String orgId, UUID account) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            created_at, updated_at, version, admission_review)
                        VALUES (:id, :org, :account, 'MEMBER', FALSE, 'ACTIVE', 'INVITATION', :now, :now, 0, FALSE)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", account.toString())
                .param("now", now)
                .update();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private HttpResponse<String> app(String path) throws Exception {
        return send("http://localhost:" + port + path);
    }

    private HttpResponse<String> management(String path) throws Exception {
        return send("http://127.0.0.1:" + managementPort + path);
    }

    private static HttpResponse<String> send(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

}
