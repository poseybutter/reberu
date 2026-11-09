package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;
import static reberu.core.tenancy.service.OrgCreationService.ORG_LIMIT;

import java.time.Instant;
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
class OrgCreationTest {

    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "team_membership",
            "team", "job_type", "org_membership", "organization", "api_idempotency_key", "account_consent",
            "account");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    private UUID account;

    @BeforeEach
    void setUp() {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        account = newAccount("creator");
    }

    @Test
    @DisplayName("조직 만들기: 가장 닫힌 기본값, 조직 관리자, 기본 직무 유형 4개, 첫 팀과 팀 관리자, 활동 기록")
    void createsOrganizationWithFirstTeam() throws Exception {
        MockHttpServletResponse response = create(account, UUID.randomUUID().toString(), body("새벽 서재", "dev"));

        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode created = jsonMapper.readTree(response.getContentAsString());
        String orgId = created.get("orgId").asString();
        String teamId = created.get("teamId").asString();

        Map<String, Object> org = jdbc.sql("SELECT * FROM organization WHERE id = :id").param("id", orgId)
                .query().singleRow();
        assertThat(org).containsEntry("name", "새벽 서재").containsEntry("state", "ACTIVE")
                .containsEntry("visibility", "PRIVATE").containsEntry("join_request_mode", "OFF")
                .containsEntry("created_by", account.toString());
        assertThat(bool(org.get("join_link_enabled"))).isFalse();
        assertThat(bool(org.get("member_admission_review"))).isTrue();
        assertThat(bool(org.get("guest_approval_required"))).isTrue();
        assertThat(bool(org.get("guest_orgspace_allowed"))).isFalse();
        assertThat(((Number) org.get("guest_max_days")).intValue()).isEqualTo(90);

        Map<String, Object> membership = jdbc.sql("SELECT * FROM org_membership WHERE org_id = :org")
                .param("org", orgId).query().singleRow();
        assertThat(membership).containsEntry("account_id", account.toString()).containsEntry("state", "ACTIVE")
                .containsEntry("kind", "MEMBER").containsEntry("joined_via", "CREATOR");
        assertThat(bool(membership.get("org_admin"))).isTrue();

        assertThat(jdbc.sql("SELECT name FROM job_type WHERE org_id = :org").param("org", orgId)
                .query(String.class).list()).containsExactlyInAnyOrder("기본", "개발", "디자인", "운영");

        Map<String, Object> team = jdbc.sql("SELECT * FROM team WHERE id = :id").param("id", teamId)
                .query().singleRow();
        assertThat(team).containsEntry("org_id", orgId).containsEntry("state", "ACTIVE")
                .containsEntry("visibility", "PRIVATE").containsEntry("name_key", "첫 팀")
                .containsEntry("report_notice_titles", "[\"배포 일정\"]");
        assertThat(jdbc.sql("SELECT name FROM job_type WHERE id = :id").param("id", team.get("job_type_id"))
                .query(String.class).single()).isEqualTo("개발");
        Map<String, Object> teamMembership = jdbc.sql("SELECT * FROM team_membership WHERE team_id = :team")
                .param("team", teamId).query().singleRow();
        assertThat(teamMembership).containsEntry("account_id", account.toString())
                .containsEntry("role", "TEAM_ADMIN").containsEntry("state", "ACTIVE");

        assertThat(jdbc.sql("SELECT action FROM org_activity_log WHERE org_id = :org").param("org", orgId)
                .query(String.class).list())
                .containsExactlyInAnyOrder("ORG_CREATED", "ORG_MEMBER_JOINED", "TEAM_CREATED");
        assertThat(jdbc.sql("SELECT action FROM team_activity_log WHERE team_id = :team").param("team", teamId)
                .query(String.class).list()).containsExactly("TEAM_MEMBER_JOINED");
    }

    @Test
    @DisplayName("API-TEST-06: 같은 Idempotency-Key로 두 번 보내면 조직 하나, 같은 응답")
    void sameKeyCreatesOnce() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = create(account, key, body("한 번만", "basic"));
        MockHttpServletResponse second = create(account, key, body("한 번만", "basic"));

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(count("organization")).isOne();
    }

    @Test
    @DisplayName("Idempotency-Key 없음은 400 INVALID_REQUEST, 조직 없음")
    void missingKeyIsRejected() throws Exception {
        MockHttpServletResponse response = create(account, null, body("키 없음", "basic"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(fields(response)).containsExactly("Idempotency-Key:REQUIRED");
        assertThat(count("organization")).isZero();
    }

    @Test
    @DisplayName("T-ORG-LIMIT: 계정당 조직 5개까지, 6번째는 409 LIMIT_EXCEEDED")
    void limitsOrganizationsPerAccount() throws Exception {
        for (int i = 1; i <= ORG_LIMIT; i++) {
            assertThat(create(account, UUID.randomUUID().toString(), body("조직 " + i, "basic")).getStatus())
                    .isEqualTo(201);
        }

        MockHttpServletResponse sixth = create(account, UUID.randomUUID().toString(), body("조직 6", "basic"));

        assertThat(sixth.getStatus()).isEqualTo(409);
        assertThat(code(sixth)).isEqualTo("LIMIT_EXCEEDED");
        assertThat(count("organization")).isEqualTo(ORG_LIMIT);
        UUID other = newAccount("other");
        assertThat(create(other, UUID.randomUUID().toString(), body("다른 계정", "basic")).getStatus())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("T-ORG-LIMIT: 상한 직전 동시 요청 여러 개 중 하나만 성공")
    void concurrentRequestsDoNotExceedLimit() throws Exception {
        for (int i = 1; i < ORG_LIMIT; i++) {
            create(account, UUID.randomUUID().toString(), body("조직 " + i, "basic"));
        }
        int attempts = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String name = "동시 " + i;
                Callable<Integer> call = () -> {
                    start.await();
                    return create(account, UUID.randomUUID().toString(), body(name, "basic")).getStatus();
                };
                results.add(executor.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses).containsOnly(201, 409).filteredOn(status -> status == 201).hasSize(1);
            assertThat(count("organization")).isEqualTo(ORG_LIMIT);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("SEC-ORG-05·13: 이름 길이·제어 문자·보이지 않는 문자·금지어(정규화 뒤 비교) 거부")
    void rejectsInvalidNames() throws Exception {
        assertThat(invalid(body("가", "basic"))).containsExactly("name:TOO_SHORT");
        assertThat(invalid(body("Reberu 스터디", "basic"))).containsExactly("name:BANNED_WORD");
        assertThat(invalid(body("ｒｅｂｅｒｕ", "basic"))).containsExactly("name:BANNED_WORD");
        assertThat(invalid(body("r.e-b e_r u", "basic"))).containsExactly("name:BANNED_WORD");
        assertThat(invalid(body("공식 모임", "basic"))).containsExactly("name:BANNED_WORD");
        assertThat(invalid(body("줄\n바꿈", "basic"))).containsExactly("name:INVALID_FORMAT");
        assertThat(invalid(body("방향‮바꿈", "basic"))).containsExactly("name:INVALID_FORMAT");
        assertThat(invalid(body("안​보임", "basic"))).containsExactly("name:INVALID_FORMAT");
        assertThat(invalid(body("가".repeat(41), "basic"))).containsExactly("name:TOO_LONG");
        assertThat(invalid(body("   ", "basic"))).containsExactly("name:REQUIRED");
        assertThat(count("organization")).isZero();
    }

    @Test
    @DisplayName("소개·시간대·첫 팀 이름·직무 유형 검사")
    void rejectsInvalidFields() throws Exception {
        assertThat(invalid(request("모임", "운영자 모임입니다", "PRIVATE", "Asia/Seoul", "첫 팀", "basic")))
                .containsExactly("tagline:BANNED_WORD");
        assertThat(invalid(request("모임", null, "PRIVATE", "Mars/Base", "첫 팀", "basic")))
                .containsExactly("timeZone:NOT_ALLOWED_VALUE");
        assertThat(invalid(request("모임", null, "PRIVATE", "Asia/Seoul", "관리자 팀", "basic")))
                .containsExactly("firstTeam.name:BANNED_WORD");
        assertThat(invalid(request("모임", null, "PRIVATE", "Asia/Seoul", "첫 팀", "legal")))
                .containsExactly("firstTeam.jobTypeKey:NOT_ALLOWED_VALUE");
        assertThat(create(account, UUID.randomUUID().toString(), Map.of("name", "모임", "visibility", "PRIVATE",
                "timeZone", "Asia/Seoul", "firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"),
                "orgAdmin", true)).getStatus()).isEqualTo(400);
        assertThat(count("organization")).isZero();
    }

    @Test
    @DisplayName("공개 범위 PUBLIC으로 만들어도 가입 요청은 꺼짐(TEN-JOIN-05)")
    void publicOrganizationKeepsJoinRequestsOff() throws Exception {
        MockHttpServletResponse response = create(account, UUID.randomUUID().toString(),
                request("공개 모임", "누구나 보는 모임", "PUBLIC", "Asia/Seoul", "첫 팀", "ops"));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT join_request_mode FROM organization").query(String.class).single())
                .isEqualTo("OFF");
    }

    @Test
    @DisplayName("기본 직무 유형 목록 4개")
    void listsDefaultJobTypes() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/default-job-types")
                .with(authentication(new AccountAuthentication(account, Instant.now())))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode types = jsonMapper.readTree(response.getContentAsString());
        List<String> keys = new ArrayList<>();
        types.forEach(type -> keys.add(type.get("key").asString()));
        assertThat(keys).containsExactly("basic", "dev", "design", "ops");
    }

    @Test
    @DisplayName("로그인 없이 조직 만들기는 401")
    void requiresAccount() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/orgs").with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(jsonMapper.writeValueAsString(body("익명", "basic")))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(count("organization")).isZero();
    }

    private MockHttpServletResponse create(UUID accountId, String key, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/orgs")
                .with(authentication(new AccountAuthentication(accountId, Instant.now())))
                .with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private List<String> invalid(Object body) throws Exception {
        MockHttpServletResponse response = create(account, UUID.randomUUID().toString(), body);
        assertThat(response.getStatus()).isEqualTo(400);
        return fields(response);
    }

    private List<String> fields(MockHttpServletResponse response) throws Exception {
        List<String> fields = new ArrayList<>();
        jsonMapper.readTree(response.getContentAsString()).get("fields")
                .forEach(field -> fields.add(field.get("field").asString() + ":" + field.get("reason").asString()));
        return fields;
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString()).get("code").asString();
    }

    private static Map<String, Object> body(String name, String jobTypeKey) {
        return request(name, null, "PRIVATE", "Asia/Seoul", "첫 팀", jobTypeKey);
    }

    private static Map<String, Object> request(String name, String tagline, String visibility, String timeZone,
            String teamName, String jobTypeKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("tagline", tagline);
        body.put("visibility", visibility);
        body.put("timeZone", timeZone);
        body.put("firstTeam", Map.of("name", teamName, "jobTypeKey", jobTypeKey));
        return body;
    }

    private UUID newAccount(String name) {
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(),
                name + "@example.com", name, null, null), "v1", "v1", Instant.now());
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag ? flag : ((Number) value).intValue() != 0;
    }

}
