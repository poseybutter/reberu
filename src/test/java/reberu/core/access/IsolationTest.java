package reberu.core.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static reberu.TestCsrf.csrfToken;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.auth.security.AccountAuthentication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IsolationTest {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private IsolationFixture data;

    @BeforeEach
    void setUp() {
        data = IsolationFixture.create(jdbc, accounts);
    }

    @Test
    @DisplayName("T-ISO-ORG·T-ISO-TEAM·TEN-CTX-01: 모든 조직·팀 엔드포인트에서 남의 조직·팀은 같은 404 본문")
    void everyScopedEndpointHidesOtherTenants() throws Exception {
        List<Endpoint> endpoints = scopedEndpoints();
        assertThat(endpoints).extracting(Endpoint::pattern)
                .contains("/api/orgs/{orgId}/teams", "/api/orgs/{orgId}/teams/{teamId}/archive", "/api/teams/{teamId}");

        JsonNode reference = null;
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Endpoint endpoint : endpoints) {
            for (Attempt attempt : attempts(endpoint)) {
                MockHttpServletResponse response = call(endpoint, attempt);
                checked++;
                String label = attempt.who() + " " + endpoint.method() + " " + endpoint.pattern();
                if (response.getStatus() != 404) {
                    failures.add(label + " -> " + response.getStatus());
                    continue;
                }
                JsonNode body = withoutTraceId(response);
                if (reference == null) {
                    reference = body;
                } else if (!reference.equals(body)) {
                    failures.add(label + " -> 본문 다름 " + body);
                }
            }
        }

        assertThat(checked).isGreaterThan(endpoints.size());
        assertThat(failures).isEmpty();
        assertThat(reference.get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("격리 기준 확인: 제 팀·조직에는 200, 조직 관리자는 팀 멤버십 없이 팀 열람")
    void ownTenantsAreReachable() throws Exception {
        JsonNode sea = json(perform(get("/api/teams/" + data.padoSea), data.daon), 200);
        assertThat(sea.get("name").asString()).isEqualTo("바다팀");
        assertThat(sea.get("myRole").asString()).isEqualTo("TEAM_ADMIN");
        assertThat(json(perform(get("/api/teams/" + data.gureumSea), data.haneul), 200).get("myRole").asString())
                .isEqualTo("TEAM_MEMBER");
        assertThat(json(perform(get("/api/teams/" + data.padoForest), data.saebom), 200).get("myRole").asString())
                .isEqualTo("TEAM_MEMBER");
        assertThat(perform(get("/api/orgs/" + data.pado + "/teams"), data.haneul).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("TEN-TST-04: 손님 만료 시각이 지나면 서버 작업 전에도 숲팀 API 404")
    void expiredGuestIsHiddenImmediately() throws Exception {
        assertThat(perform(get("/api/teams/" + data.padoForest), data.saebom).getStatus()).isEqualTo(200);

        data.expireGuest();

        assertThat(perform(get("/api/teams/" + data.padoForest), data.saebom).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("TEN-TST-03: 손님은 조직 단위 API(공개 팀 목록)와 다른 팀이 404")
    void guestSeesOnlyOwnTeam() throws Exception {
        assertThat(perform(get("/api/orgs/" + data.pado + "/teams"), data.saebom).getStatus()).isEqualTo(404);
        assertThat(perform(get("/api/teams/" + data.padoSea), data.saebom).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("TEN-PERM-04·05·TEN-TST-05: 도윤(조직 관리자) 팀 열람은 역할 없이 200, 조직 기록에 1시간에 한 번만, 팀 기록에는 없음")
    void orgAdminViewIsRecordedOnceAndHiddenFromTeam() throws Exception {
        JsonNode first = json(perform(get("/api/teams/" + data.padoSea), data.doyun), 200);
        perform(get("/api/teams/" + data.padoSea), data.doyun);
        perform(get("/api/teams/" + data.padoForest), data.doyun);
        perform(get("/api/teams/" + data.padoSea), data.daon);

        assertThat(first.get("myRole").isNull()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT target_id FROM org_activity_log
                        WHERE action = 'ORG_ADMIN_VIEWED_TEAM' AND actor_id = :actor ORDER BY occurred_at
                        """)
                .param("actor", data.doyun.toString()).query(String.class).list())
                .containsExactly(data.padoSea.toString(), data.padoForest.toString());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM team_activity_log").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM org_activity_log WHERE actor_id = :actor")
                .param("actor", data.daon.toString()).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("TEN-CTX: 형식이 틀린 조직·팀 식별자도 같은 404")
    void malformedIdentifiersAreNotFound() throws Exception {
        for (String path : List.of("/api/teams/not-a-uuid", "/api/teams/" + data.padoSea.toString().toUpperCase(),
                "/api/orgs/not-a-uuid/teams")) {
            assertThat(perform(get(path), data.daon).getStatus()).as(path).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("TEN-CTX: context-path와 인코딩된 경로도 같은 판정, 제 팀 200·남의 팀 같은 404")
    void contextPathAndEncodedPathAreDecided() throws Exception {
        List<MockHttpServletRequestBuilder> own = List.of(
                get("/app/api/teams/" + data.padoSea).contextPath("/app"),
                get(URI.create("/%61pi/teams/" + data.padoSea)));
        List<MockHttpServletRequestBuilder> others = List.of(
                get("/app/api/teams/" + data.gureumSea).contextPath("/app"),
                get(URI.create("/%61pi/teams/" + data.gureumSea)));

        for (MockHttpServletRequestBuilder request : own) {
            assertThat(json(perform(request, data.daon), 200).get("myRole").asString()).isEqualTo("TEAM_ADMIN");
        }
        for (MockHttpServletRequestBuilder request : others) {
            assertThat(json(perform(request, data.daon), 404).get("code").asString()).isEqualTo("NOT_FOUND");
        }
    }

    private List<Attempt> attempts(Endpoint endpoint) {
        List<Attempt> attempts = new ArrayList<>();
        UUID unknown = UUID.randomUUID();
        if (endpoint.pattern().contains("{teamId}") && !endpoint.pattern().contains("{orgId}")) {
            attempts.add(new Attempt("다온→구름 바다팀", data.daon, null, data.gureumSea));
            attempts.add(new Attempt("하늘→파도 숲팀", data.haneul, null, data.padoForest));
            attempts.add(new Attempt("새봄→파도 바다팀", data.saebom, null, data.padoSea));
            attempts.add(new Attempt("이슬→파도 바다팀", data.iseul, null, data.padoSea));
            attempts.add(new Attempt("다온→없는 팀", data.daon, null, unknown));
            return attempts;
        }
        attempts.add(new Attempt("다온→구름", data.daon, data.gureum, data.gureumSea));
        attempts.add(new Attempt("이슬→파도", data.iseul, data.pado, data.padoSea));
        attempts.add(new Attempt("새봄→파도(손님)", data.saebom, data.pado, data.padoForest));
        attempts.add(new Attempt("다온→없는 조직", data.daon, unknown, unknown));
        if (endpoint.pattern().contains("{teamId}")) {
            attempts.add(new Attempt("도윤→파도 경로의 구름 팀", data.doyun, data.pado, data.gureumSea));
            attempts.add(new Attempt("새봄(구름 관리자)→구름 경로의 파도 팀", data.saebom, data.gureum, data.padoSea));
        }
        return attempts;
    }

    private MockHttpServletResponse call(Endpoint endpoint, Attempt attempt) throws Exception {
        Matcher matcher = VARIABLE.matcher(endpoint.pattern());
        StringBuilder path = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "orgId" -> attempt.orgId().toString();
                case "teamId" -> attempt.teamId().toString();
                default -> UUID.randomUUID().toString();
            };
            matcher.appendReplacement(path, value);
        }
        matcher.appendTail(path);
        return perform(request(HttpMethod.valueOf(endpoint.method().name()), path.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"), attempt.account());
    }

    private List<Endpoint> scopedEndpoints() {
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/orgs/{orgId}") && !pattern.startsWith("/api/teams/{teamId}")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    endpoints.put(method + " " + pattern, new Endpoint(method, pattern));
                }
            }
        }
        return List.copyOf(endpoints.values());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID account) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(account, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        return jsonMapper.readTree(response.getContentAsString());
    }

    private JsonNode withoutTraceId(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) jsonMapper.readTree(response.getContentAsString());
        body.remove("traceId");
        return body;
    }

    private record Endpoint(RequestMethod method, String pattern) {
    }

    private record Attempt(String who, UUID account, UUID orgId, UUID teamId) {
    }

}
