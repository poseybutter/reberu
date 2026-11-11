package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import reberu.core.tenancy.service.JobTypeService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JobTypeTest {

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

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID admin;
    private UUID member;
    private UUID guest;
    private UUID outsider;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = newAccount("admin");
        member = newAccount("member");
        guest = newAccount("guest");
        outsider = newAccount("outsider");
        orgId = json(createOrg(admin, "새벽 서재")).get("orgId").asString();
        insertOrgMembership(orgId, member, "MEMBER");
        insertOrgMembership(orgId, guest, "GUEST");
    }

    @Test
    @DisplayName("목록: 기본 4개와 쓰는 팀 수·version, 숨긴 유형은 includeHidden일 때만, 구성원 403, 손님 404")
    void listsJobTypes() throws Exception {
        JsonNode list = json(perform(get(path()), admin), 200);

        assertThat(names(list)).containsExactly("기본", "개발", "디자인", "운영");
        assertThat(jdbc.sql("SELECT COUNT(DISTINCT created_at) FROM job_type WHERE org_id = :org")
                .param("org", orgId).query(Integer.class).single()).isEqualTo(4);
        assertThat(list.get(0).get("teamCount").asLong()).isOne();
        assertThat(list.get(0).get("version").asInt()).isZero();
        assertThat(list.get(1).get("defaults").get("taskLabels").get(0).asString()).isEqualTo("기능");
        assertThat(perform(post(path() + "/" + jobType("운영") + "/hide"), admin).getStatus()).isEqualTo(204);
        assertThat(names(json(perform(get(path()), admin), 200))).doesNotContain("운영");
        assertThat(names(json(perform(get(path() + "?includeHidden=true"), admin), 200))).contains("운영");
        assertThat(perform(get(path()), member).getStatus()).isEqualTo(403);
        assertThat(perform(get(path()), guest).getStatus()).isEqualTo(404);
        assertThat(perform(get(path()), outsider).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("추가: 201, 앞뒤 공백 정리, 활동 기록, 같은 키 다시 보내면 같은 응답")
    void createsJobType() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = jobTypeBody(" 연구 ", List.of(" 실험 ", "정리"), List.of("주간 발표"), 3);

        MockHttpServletResponse created = create(body, key);
        MockHttpServletResponse replayed = create(body, key);

        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(replayed.getContentAsString()).isEqualTo(created.getContentAsString());
        String id = json(created).get("jobTypeId").asString();
        Map<String, Object> row = jdbc.sql("SELECT name, name_key, hidden, defaults FROM job_type WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(row).containsEntry("name", "연구").containsEntry("name_key", "연구");
        assertThat(jsonMapper.readTree((String) row.get("defaults")).get("taskLabels").get(0).asString())
                .isEqualTo("실험");
        assertThat(logCount("JOB_TYPE_CREATED")).isOne();
        assertThat(count()).isEqualTo(5);
    }

    @Test
    @DisplayName("이름은 조직 안에서 유일: 대소문자·공백·전각만 다른 이름은 DUPLICATE, 다른 조직은 같은 이름 가능")
    void namesAreUniquePerOrg() throws Exception {
        assertThat(create(jobTypeBody("Research  Team", List.of(), List.of(), 1)).getStatus()).isEqualTo(201);

        assertThat(fields(create(jobTypeBody(" research team ", List.of(), List.of(), 1))))
                .containsExactly("name:DUPLICATE");
        assertThat(fields(create(jobTypeBody("ＲＥＳＥＡＲＣＨ ＴＥＡＭ", List.of(), List.of(), 1))))
                .containsExactly("name:DUPLICATE");
        assertThat(fields(create(jobTypeBody("개발", List.of(), List.of(), 1)))).containsExactly("name:DUPLICATE");
        String otherOrg = json(createOrg(outsider, "다른 조직")).get("orgId").asString();
        assertThat(perform(post("/api/orgs/" + otherOrg + "/job-types").header("Idempotency-Key",
                        UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(jobTypeBody("Research Team", List.of(), List.of(), 1))),
                outsider).getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("기본값 검사: 모듈 key 형식, 라벨 개수·중복·빈 값, 공지 칸 길이, 주 시작 요일, 빠진 값")
    void validatesDefaults() throws Exception {
        Map<String, Object> badDefaults = defaults(List.of(), List.of(), 1);
        badDefaults.put("modules", List.of("Bad_Key"));
        Map<String, Object> badModule = jobTypeBody("가", List.of(), List.of(), 1);
        badModule.put("defaults", badDefaults);
        assertThat(fields(create(badModule))).containsExactly("defaults.modules:INVALID_FORMAT");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= JobTypeService.LABELS_MAX; i++) {
            tooMany.add("라벨" + i);
        }
        assertThat(fields(create(jobTypeBody("나", tooMany, List.of(), 1))))
                .containsExactly("defaults.taskLabels:TOO_LONG");
        assertThat(fields(create(jobTypeBody("다", List.of("회의", " 회의 "), List.of(), 1))))
                .containsExactly("defaults.taskLabels:DUPLICATE");
        assertThat(fields(create(jobTypeBody("라", List.of(" "), List.of(), 1))))
                .containsExactly("defaults.taskLabels:REQUIRED");
        assertThat(fields(create(jobTypeBody("마", List.of("가".repeat(31)), List.of(), 1))))
                .containsExactly("defaults.taskLabels:TOO_LONG");
        assertThat(fields(create(jobTypeBody("바", List.of(), List.of(), 8))))
                .containsExactly("defaults.reportWeekStart:NOT_ALLOWED_VALUE");
        assertThat(fields(create(jobTypeBody("사", List.of(), List.of(), 0))))
                .containsExactly("defaults.reportWeekStart:NOT_ALLOWED_VALUE");
        Map<String, Object> noDefaults = new LinkedHashMap<>();
        noDefaults.put("name", "아");
        assertThat(fields(create(noDefaults))).containsExactly("defaults:REQUIRED");
        assertThat(count()).isEqualTo(4);
    }

    @Test
    @DisplayName("조직당 50개까지, 51번째는 409 LIMIT_EXCEEDED")
    void limitsJobTypesPerOrg() throws Exception {
        for (int i = count(); i < JobTypeService.PER_ORG_MAX; i++) {
            assertThat(create(jobTypeBody("유형 " + i, List.of(), List.of(), 1)).getStatus()).isEqualTo(201);
        }

        MockHttpServletResponse over = create(jobTypeBody("넘침", List.of(), List.of(), 1));

        assertThat(over.getStatus()).isEqualTo(409);
        assertThat(code(over)).isEqualTo("LIMIT_EXCEEDED");
        assertThat(count()).isEqualTo(JobTypeService.PER_ORG_MAX);
    }

    @Test
    @DisplayName("고치기: 보낸 값만 바뀌고 version 증가, 이미 만든 팀은 그대로, 같은 version 두 번이면 409")
    void updatesJobType() throws Exception {
        UUID basic = jobType("기본");
        String teamTitlesBefore = teamNoticeTitles();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 0);
        body.put("name", "기본 업무");
        body.put("defaults", defaults(List.of("일반"), List.of("새 공지"), 2));

        JsonNode updated = json(update(basic, body), 200);
        MockHttpServletResponse stale = update(basic, body);

        assertThat(updated.get("name").asString()).isEqualTo("기본 업무");
        assertThat(updated.get("version").asInt()).isOne();
        assertThat(updated.get("defaults").get("noticeTitles").get(0).asString()).isEqualTo("새 공지");
        assertThat(teamNoticeTitles()).isEqualTo(teamTitlesBefore);
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
        assertThat(jdbc.sql("SELECT after_value FROM org_activity_log WHERE action = 'JOB_TYPE_CHANGED'")
                .query(String.class).single()).isEqualTo("name;defaults");
    }

    @Test
    @DisplayName("고치기 이름 검사: 다른 유형과 겹치면 DUPLICATE, 자기 이름의 대소문자만 바꾸기는 가능")
    void renameChecksDuplicates() throws Exception {
        String id = json(create(jobTypeBody("Ops Desk", List.of(), List.of(), 1))).get("jobTypeId").asString();

        assertThat(fields(update(UUID.fromString(id), Map.of("version", 0, "name", "디자인"))))
                .containsExactly("name:DUPLICATE");
        assertThat(json(update(UUID.fromString(id), Map.of("version", 0, "name", "OPS desk")), 200)
                .get("name").asString()).isEqualTo("OPS desk");
    }

    @Test
    @DisplayName("고치기 이름 경합: 커밋 전인 같은 이름과 겹치면 유일 제약으로 막고 DUPLICATE")
    void renameRaceHitsUniqueConstraint() throws Exception {
        UUID ops = jobType("운영");
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            jdbc.sql("""
                            INSERT INTO job_type (id, org_id, name, name_key, description, hidden, defaults,
                                created_at, updated_at, version)
                            VALUES (:id, :org, '경합', '경합', NULL, :hidden, '{}', :now, :now, 0)
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("org", orgId)
                    .param("hidden", false)
                    .param("now", now)
                    .update();
            inserted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<MockHttpServletResponse> rename = CompletableFuture.supplyAsync(() -> {
            try {
                return update(ops, Map.of("version", 0, "name", "경합"));
            }
            catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        Thread.sleep(500);
        release.countDown();
        holder.join();

        assertThat(fields(rename.get(30, TimeUnit.SECONDS))).containsExactly("name:DUPLICATE");
        assertThat(jdbc.sql("SELECT name FROM job_type WHERE id = :id").param("id", ops.toString())
                .query(String.class).single()).isEqualTo("운영");
    }

    @Test
    @DisplayName("T-AUTHZ-MASS: 본문에 숨김·소속·이름 키를 끼워 넣으면 400")
    void rejectsMassAssignment() throws Exception {
        UUID basic = jobType("기본");
        for (String extra : List.of("hidden", "orgId", "nameKey", "id")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("version", 0);
            body.put(extra, "hidden".equals(extra) ? (Object) true : UUID.randomUUID().toString());

            assertThat(update(basic, body).getStatus()).as(extra).isEqualTo(400);
        }
        Map<String, Object> createBody = jobTypeBody("끼워 넣기", List.of(), List.of(), 1);
        createBody.put("hidden", true);
        assertThat(create(createBody).getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT version FROM job_type WHERE id = :id").param("id", basic.toString())
                .query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("숨기기·다시 보이기: 204, 반복은 409 ALREADY_PROCESSED, 숨긴 유형으로 팀 만들기 거부")
    void hidesAndUnhides() throws Exception {
        UUID design = jobType("디자인");

        assertThat(perform(post(path() + "/" + design + "/hide"), admin).getStatus()).isEqualTo(204);
        MockHttpServletResponse again = perform(post(path() + "/" + design + "/hide"), admin);
        Map<String, Object> team = new LinkedHashMap<>();
        team.put("name", "디자인 팀");
        team.put("visibility", "PRIVATE");
        team.put("jobTypeId", design.toString());
        team.put("firstAdminAccountId", admin.toString());
        MockHttpServletResponse teamWithHidden = perform(post("/api/orgs/" + orgId + "/teams")
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(team)), admin);

        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("ALREADY_PROCESSED");
        assertThat(fields(teamWithHidden)).containsExactly("jobTypeId:NOT_ALLOWED_VALUE");
        assertThat(perform(post(path() + "/" + design + "/unhide"), admin).getStatus()).isEqualTo(204);
        assertThat(perform(post(path() + "/" + design + "/unhide"), admin).getStatus()).isEqualTo(409);
        assertThat(logCount("JOB_TYPE_HIDDEN")).isOne();
        assertThat(logCount("JOB_TYPE_UNHIDDEN")).isOne();
    }

    @Test
    @DisplayName("다른 조직의 직무 유형은 내 조직 경로로 404, 보관한 조직의 추가는 409 READ_ONLY")
    void isolatesAndRespectsOrgState() throws Exception {
        String otherOrg = json(createOrg(outsider, "다른 조직")).get("orgId").asString();
        UUID foreign = UUID.fromString(jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", otherOrg).query(String.class).single());

        assertThat(perform(post(path() + "/" + foreign + "/hide"), admin).getStatus()).isEqualTo(404);
        assertThat(update(foreign, Map.of("version", 0, "name", "남의 것")).getStatus()).isEqualTo(404);

        jdbc.sql("UPDATE organization SET state = 'ARCHIVED' WHERE id = :org").param("org", orgId).update();
        MockHttpServletResponse locked = create(jobTypeBody("보관 중", List.of(), List.of(), 1));
        assertThat(locked.getStatus()).isEqualTo(409);
        assertThat(code(locked)).isEqualTo("READ_ONLY");
        assertThat(perform(get(path()), admin).getStatus()).isEqualTo(200);
    }

    private String path() {
        return "/api/orgs/" + orgId + "/job-types";
    }

    private static Map<String, Object> jobTypeBody(String name, List<String> labels, List<String> titles,
            int weekStart) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "설명");
        body.put("defaults", defaults(labels, titles, weekStart));
        return body;
    }

    private static Map<String, Object> defaults(List<String> labels, List<String> titles, int weekStart) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("modules", List.of());
        defaults.put("taskLabels", labels);
        defaults.put("noticeTitles", titles);
        defaults.put("reportWeekStart", weekStart);
        return defaults;
    }

    private MockHttpServletResponse create(Map<String, Object> body) throws Exception {
        return create(body, UUID.randomUUID().toString());
    }

    private MockHttpServletResponse create(Map<String, Object> body, String key) throws Exception {
        return perform(post(path()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)), admin);
    }

    private MockHttpServletResponse update(UUID jobTypeId, Map<String, Object> body) throws Exception {
        return perform(patch(path() + "/" + jobTypeId).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)), admin);
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

    private void insertOrgMembership(String org, UUID account, String kind) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version)
                        VALUES (:id, :org, :account, :kind, :admin, 'ACTIVE', 'INVITATION', :expires, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", org)
                .param("account", account.toString())
                .param("kind", kind)
                .param("admin", false)
                .param("expires", "GUEST".equals(kind) ? now.plusDays(30) : null)
                .param("now", now)
                .update();
    }

    private UUID jobType(String name) {
        return UUID.fromString(jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = :name")
                .param("org", orgId).param("name", name).query(String.class).single());
    }

    private String teamNoticeTitles() {
        return jdbc.sql("SELECT report_notice_titles FROM team WHERE org_id = :org").param("org", orgId)
                .query(String.class).single();
    }

    private int count() {
        return jdbc.sql("SELECT COUNT(*) FROM job_type WHERE org_id = :org").param("org", orgId)
                .query(Integer.class).single();
    }

    private int logCount(String action) {
        return jdbc.sql("SELECT COUNT(*) FROM org_activity_log WHERE org_id = :org AND action = :action")
                .param("org", orgId).param("action", action).query(Integer.class).single();
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    private List<String> fields(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
        List<String> fields = new ArrayList<>();
        json(response).get("fields")
                .forEach(field -> fields.add(field.get("field").asString() + ":" + field.get("reason").asString()));
        return fields;
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return json(response).get("code").asString();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString());
    }

    private JsonNode json(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json(response);
    }

    private UUID newAccount(String name) {
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(),
                name + "@example.com", name, null, null), "v1", "v1", Instant.now());
    }

}
