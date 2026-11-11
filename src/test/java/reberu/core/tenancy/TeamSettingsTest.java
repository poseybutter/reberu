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
import reberu.core.tenancy.service.TeamFieldService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TeamSettingsTest {

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
        outsider = newAccount("outsider");
        JsonNode created = json(createOrg(orgAdmin, "새벽 서재"), 201);
        orgId = created.get("orgId").asString();
        String firstTeam = created.get("teamId").asString();
        insertOrgMembership(teamAdmin, "MEMBER");
        insertOrgMembership(member, "MEMBER");
        insertOrgMembership(viewer, "MEMBER");
        insertOrgMembership(guest, "GUEST");
        teamId = createTeam("개발 팀", teamAdmin, "개발");
        insertTeamMembership(teamId, member, "TEAM_MEMBER", null);
        insertTeamMembership(teamId, viewer, "VIEWER", null);
        insertTeamMembership(teamId, guest, "TEAM_MEMBER", LocalDateTime.now(ZoneOffset.UTC).plusDays(3));
        assertThat(firstTeam).isNotNull();
    }

    @Test
    @DisplayName("TEN-JOB-02: 팀을 만들면 직무 유형의 업무 구분 라벨을 순서대로 복사, 조직 첫 팀도 같음")
    void copiesJobTypeLabels() throws Exception {
        String firstTeam = jdbc.sql("SELECT id FROM team WHERE org_id = :org AND name = '첫 팀'").param("org", orgId)
                .query(String.class).single();

        assertThat(names(json(perform(get(teamPath("/labels")), member), 200)))
                .containsExactly("기능", "버그", "개선", "문서");
        assertThat(names(json(perform(get("/api/teams/" + firstTeam + "/labels"), orgAdmin), 200)))
                .containsExactly("일반", "회의", "문서");
    }

    @Test
    @DisplayName("팀 설정 조회: 팀 관리자·조직 관리자 200, 팀원·읽기 전용·손님 403(API-TADM-02), 팀 밖 404")
    void readsSettings() throws Exception {
        JsonNode settings = json(perform(get(teamPath("/settings")), teamAdmin), 200);

        assertThat(settings.get("name").asString()).isEqualTo("개발 팀");
        assertThat(settings.get("jobTypeName").asString()).isEqualTo("개발");
        assertThat(settings.get("visibility").asString()).isEqualTo("PRIVATE");
        assertThat(settings.get("joinMode").asString()).isEqualTo("APPROVAL");
        assertThat(settings.get("noticeTitles").get(0).asString()).isEqualTo("배포 일정");
        assertThat(settings.get("version").asInt()).isZero();
        assertThat(perform(get(teamPath("/settings")), orgAdmin).getStatus()).isEqualTo(200);
        for (UUID denied : List.of(member, viewer, guest)) {
            assertThat(code(perform(get(teamPath("/settings")), denied), 403)).isEqualTo("FORBIDDEN_ACTION");
            assertThat(perform(get(teamPath("/overview")), denied).getStatus()).isEqualTo(403);
        }
        assertThat(perform(get(teamPath("/settings")), outsider).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("팀 설정 고치기: 보낸 값만, version 증가, 활동 기록(이름·공지 제목 원문 없음), 같은 version 두 번이면 409")
    void updatesSettings() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 0);
        body.put("name", " 개발 1팀 ");
        body.put("visibility", "PUBLIC");
        body.put("joinMode", "INSTANT");
        body.put("noticeTitles", List.of(" 이번 주 배포 ", "회고"));

        JsonNode updated = json(updateSettings(teamAdmin, body), 200);
        MockHttpServletResponse stale = updateSettings(teamAdmin, body);

        assertThat(updated.get("name").asString()).isEqualTo("개발 1팀");
        assertThat(updated.get("visibility").asString()).isEqualTo("PUBLIC");
        assertThat(updated.get("joinMode").asString()).isEqualTo("INSTANT");
        assertThat(updated.get("reportWeekStart").asInt()).isOne();
        assertThat(updated.get("noticeTitles").get(0).asString()).isEqualTo("이번 주 배포");
        assertThat(updated.get("version").asInt()).isOne();
        assertThat(code(stale, 409)).isEqualTo("VERSION_CONFLICT");
        assertThat(teamLog("TEAM_SETTINGS_CHANGED")).containsExactly(
                "name;visibility=PRIVATE;joinMode=APPROVAL;noticeTitles"
                        + " | name;visibility=PUBLIC;joinMode=INSTANT;noticeTitles");

        JsonNode same = json(updateSettings(teamAdmin, Map.of("version", 1, "name", "개발 1팀")), 200);
        assertThat(same.get("version").asInt()).isOne();
        assertThat(teamLog("TEAM_SETTINGS_CHANGED")).hasSize(1);
        assertThat(perform(patch(teamPath("/settings")).contentType(MediaType.APPLICATION_JSON).content("{}"),
                teamAdmin).getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("팀 설정 값 검사: 다른 팀과 같은 이름 키 DUPLICATE, 주 시작 요일, 공지 칸 개수·길이, 금지 필드 400")
    void validatesSettings() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 0);
        body.put("name", "첫  팀");
        body.put("reportWeekStart", 8);
        body.put("noticeTitles", List.of("1", "2", "3", "4", "5", "6"));

        assertThat(fields(updateSettings(teamAdmin, body))).containsExactlyInAnyOrder("name:DUPLICATE",
                "reportWeekStart:NOT_ALLOWED_VALUE", "noticeTitles:TOO_LONG");
        assertThat(fields(updateSettings(teamAdmin, Map.of("version", 0, "noticeTitles", List.of("공지", " 공지 ")))))
                .containsExactly("noticeTitles:DUPLICATE");
        for (String extra : List.of("state", "orgId", "jobTypeId", "nameKey", "id")) {
            Map<String, Object> mass = new LinkedHashMap<>();
            mass.put("version", 0);
            mass.put(extra, "ARCHIVED");
            assertThat(updateSettings(teamAdmin, mass).getStatus()).as(extra).isEqualTo(400);
        }
        assertThat(teamVersion()).isZero();
    }

    @Test
    @DisplayName("보관한 팀: 설정·라벨·추가 항목 쓰기는 409 READ_ONLY, 읽기는 됨, 팀원은 설정 쓰기 403")
    void archivedTeamIsReadOnly() throws Exception {
        assertThat(updateSettings(member, Map.of("version", 0, "name", "x")).getStatus()).isEqualTo(403);
        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :id").param("id", teamId).update();

        assertThat(code(updateSettings(teamAdmin, Map.of("version", 0, "name", "보관 중")), 409)).isEqualTo("READ_ONLY");
        assertThat(code(createLabel(teamAdmin, "보관"), 409)).isEqualTo("READ_ONLY");
        assertThat(code(createField(teamAdmin, "TASK", "보관", "TEXT"), 409)).isEqualTo("READ_ONLY");
        assertThat(perform(get(teamPath("/settings")), teamAdmin).getStatus()).isEqualTo(200);
        assertThat(perform(get(teamPath("/labels")), member).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("TEN-TSET-07: 개요는 팀 가입 요청 수, 7일 안 손님 만료 수, 최근 팀 활동 5개(값 없음)")
    void readsOverview() throws Exception {
        UUID requester = newAccount("requester");
        insertOrgMembership(requester, "MEMBER");
        insertTeamMembership(teamId, requester, "TEAM_MEMBER", null, "REQUESTED");
        UUID laterGuest = newAccount("laterguest");
        insertOrgMembership(laterGuest, "GUEST");
        insertTeamMembership(teamId, laterGuest, "TEAM_MEMBER", LocalDateTime.now(ZoneOffset.UTC).plusDays(30));
        assertThat(createLabel(teamAdmin, "회의").getStatus()).isEqualTo(201);

        JsonNode overview = json(perform(get(teamPath("/overview")), teamAdmin), 200);

        assertThat(overview.get("pendingJoinRequests").asLong()).isOne();
        assertThat(overview.get("expiringGuests").asLong()).isOne();
        assertThat(overview.get("recentActivity").get(0).get("action").asString()).isEqualTo("TEAM_LABEL_CREATED");
        assertThat(overview.get("recentActivity").get(0).has("afterValue")).isFalse();
        assertThat(overview.get("recentActivity").size()).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("라벨: 추가 201·끝 순서, 이름 키 중복 400, 고치기 version, 숨긴 라벨은 관리자가 includeHidden일 때만")
    void managesLabels() throws Exception {
        String labelId = json(createLabel(teamAdmin, " 회의 "), 201).get("labelId").asString();

        assertThat(fields(createLabel(teamAdmin, "　회의 "))).containsExactly("name:DUPLICATE");
        assertThat(fields(createLabel(teamAdmin, "기능"))).containsExactly("name:DUPLICATE");
        JsonNode created = labels(teamAdmin, true).get(4);
        assertThat(created.get("name").asString()).isEqualTo("회의");
        assertThat(created.get("sortOrder").asInt()).isEqualTo(4);

        JsonNode changed = json(updateLabel(labelId, Map.of("version", 0, "name", "회의록", "sortOrder", 0,
                "hidden", true)), 200);
        assertThat(changed.get("version").asInt()).isOne();
        assertThat(code(updateLabel(labelId, Map.of("version", 0, "hidden", false)), 409))
                .isEqualTo("VERSION_CONFLICT");
        assertThat(fields(updateLabel(labelId, Map.of("version", 1, "name", "버그")))).containsExactly("name:DUPLICATE");
        assertThat(fields(updateLabel(labelId, Map.of("version", 1, "sortOrder", 10000))))
                .containsExactly("sortOrder:NOT_ALLOWED_VALUE");
        assertThat(names(labels(member, true))).doesNotContain("회의록");
        assertThat(names(labels(teamAdmin, false))).doesNotContain("회의록");
        assertThat(names(labels(teamAdmin, true))).startsWith("기능", "회의록");
        assertThat(names(labels(orgAdmin, true))).contains("회의록");
        assertThat(teamLog("TEAM_LABEL_CHANGED")).containsExactly("- | name;sortOrder=0;hidden=true");
        assertThat(createLabel(member, "팀원 라벨").getStatus()).isEqualTo(403);
        assertThat(updateLabel(labelId, Map.of("version", 1, "hidden", false), viewer).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("TEN-TSET-04: 라벨은 팀당 50개(숨김 포함), 51번째 409, 동시에 두 개 넣어도 50개를 넘지 않음")
    void limitsLabels() throws Exception {
        for (int i = 4; i < TeamFieldService.LABELS_PER_TEAM_MAX - 1; i++) {
            assertThat(createLabel(teamAdmin, "라벨 " + i).getStatus()).isEqualTo(201);
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> race(start, "경합 1"), executor);
            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> race(start, "경합 2"), executor);
            start.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM team_task_label WHERE team_id = :team").param("team", teamId)
                .query(Integer.class).single()).isEqualTo(TeamFieldService.LABELS_PER_TEAM_MAX);
    }

    @Test
    @DisplayName("추가 항목: 대상별 이름 유일, 대상·종류는 바꿀 수 없음, 필수·숨김 고치기, 대상마다 20개")
    void managesCustomFields() throws Exception {
        String fieldId = json(createField(teamAdmin, "TASK", "고객사", "TEXT"), 201).get("fieldId").asString();

        assertThat(createField(teamAdmin, "PROJECT", "고객사", "MEMO").getStatus()).isEqualTo(201);
        assertThat(fields(createField(teamAdmin, "TASK", " 고객사", "MEMO"))).containsExactly("label:DUPLICATE");
        assertThat(createField(teamAdmin, "TEAM", "대상 없음", "TEXT").getStatus()).isEqualTo(400);
        assertThat(createField(teamAdmin, "TASK", "종류 없음", "FILE").getStatus()).isEqualTo(400);
        JsonNode changed = json(perform(patch(teamPath("/custom-fields/" + fieldId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("version", 0, "required", true, "hidden", true))),
                teamAdmin), 200);
        assertThat(changed.get("required").asBoolean()).isTrue();
        assertThat(changed.get("target").asString()).isEqualTo("TASK");
        for (String immutable : List.of("target", "kind")) {
            assertThat(perform(patch(teamPath("/custom-fields/" + fieldId)).contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(Map.of("version", 1, immutable, "PROJECT"))), teamAdmin)
                    .getStatus()).as(immutable).isEqualTo(400);
        }
        assertThat(customFieldLabels(member)).containsExactly("고객사");
        assertThat(teamLog("TEAM_FIELD_CREATED")).contains("- | target=TASK;kind=TEXT");

        for (int i = 1; i < TeamFieldService.FIELDS_PER_TARGET_MAX; i++) {
            assertThat(createField(teamAdmin, "TASK", "항목 " + i, "TEXT").getStatus()).isEqualTo(201);
        }
        assertThat(code(createField(teamAdmin, "TASK", "넘침", "TEXT"), 409)).isEqualTo("LIMIT_EXCEEDED");
        assertThat(createField(teamAdmin, "PROJECT", "프로젝트 항목", "TEXT").getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("API-TADM-03: 다른 팀의 라벨·추가 항목은 내 팀 경로로 404, 다른 조직 관리자는 팀 404")
    void isolatesLabelsAndFields() throws Exception {
        String otherTeam = createTeam("다른 팀", teamAdmin, "기본");
        String foreignLabel = labels(teamAdmin, otherTeam).get(0).get("id").asString();
        String foreignField = json(perform(post("/api/teams/" + otherTeam + "/custom-fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("target", "TASK", "label", "남의 항목", "kind", "TEXT"))),
                teamAdmin), 201).get("fieldId").asString();
        jdbc.sql("UPDATE team_membership SET role = 'TEAM_MEMBER' WHERE team_id = :team AND account_id = :account")
                .param("team", otherTeam).param("account", teamAdmin.toString()).update();
        String otherOrg = json(createOrg(outsider, "다른 조직"), 201).get("orgId").asString();

        assertThat(updateLabel(foreignLabel, Map.of("version", 0, "hidden", true)).getStatus()).isEqualTo(404);
        assertThat(perform(patch(teamPath("/custom-fields/" + foreignField)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"hidden\":true}"), teamAdmin).getStatus()).isEqualTo(404);
        assertThat(perform(get(teamPath("/labels")), outsider).getStatus()).isEqualTo(404);
        assertThat(otherOrg).isNotNull();
    }

    private int race(CountDownLatch start, String name) {
        try {
            start.await();
            return createLabel(teamAdmin, name).getStatus();
        }
        catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private String teamPath(String suffix) {
        return "/api/teams/" + teamId + suffix;
    }

    private MockHttpServletResponse updateSettings(UUID actor, Map<String, Object> body) throws Exception {
        return perform(patch(teamPath("/settings")).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)), actor);
    }

    private MockHttpServletResponse createLabel(UUID actor, String name) throws Exception {
        return perform(post(teamPath("/labels")).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("name", name))), actor);
    }

    private MockHttpServletResponse updateLabel(String labelId, Map<String, Object> body) throws Exception {
        return updateLabel(labelId, body, teamAdmin);
    }

    private MockHttpServletResponse updateLabel(String labelId, Map<String, Object> body, UUID actor)
            throws Exception {
        return perform(patch(teamPath("/labels/" + labelId)).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)), actor);
    }

    private MockHttpServletResponse createField(UUID actor, String target, String label, String kind)
            throws Exception {
        return perform(post(teamPath("/custom-fields")).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("target", target, "label", label, "kind", kind))),
                actor);
    }

    private JsonNode labels(UUID actor, boolean includeHidden) throws Exception {
        return json(perform(get(teamPath("/labels?includeHidden=" + includeHidden)), actor), 200);
    }

    private JsonNode labels(UUID actor, String team) throws Exception {
        return json(perform(get("/api/teams/" + team + "/labels"), actor), 200);
    }

    private List<String> customFieldLabels(UUID actor) throws Exception {
        List<String> labels = new ArrayList<>();
        json(perform(get(teamPath("/custom-fields?includeHidden=true")), actor), 200)
                .forEach(field -> labels.add(field.get("label").asString()));
        return labels;
    }

    private String createTeam(String name, UUID firstAdmin, String jobTypeName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = :name")
                .param("org", orgId).param("name", jobTypeName).query(String.class).single());
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

    private void insertOrgMembership(UUID account, String kind) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version)
                        VALUES (:id, :org, :account, :kind, :admin, 'ACTIVE', 'INVITATION', :expires, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", account.toString())
                .param("kind", kind)
                .param("admin", false)
                .param("expires", "GUEST".equals(kind) ? now.plusDays(60) : null)
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String team, UUID account, String role, LocalDateTime expiresAt) {
        insertTeamMembership(team, account, role, expiresAt, "ACTIVE");
    }

    private void insertTeamMembership(String team, UUID account, String role, LocalDateTime expiresAt,
            String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, expires_at,
                            created_at, updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'DIRECT', :expires, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", team)
                .param("account", account.toString())
                .param("role", role)
                .param("state", state)
                .param("expires", expiresAt)
                .param("now", now)
                .update();
    }

    private int teamVersion() {
        return jdbc.sql("SELECT version FROM team WHERE id = :id").param("id", teamId).query(Integer.class).single();
    }

    private List<String> teamLog(String action) {
        return jdbc.sql("""
                        SELECT before_value, after_value FROM team_activity_log
                        WHERE team_id = :team AND action = :action ORDER BY occurred_at
                        """)
                .param("team", teamId).param("action", action)
                .query((rs, rowNum) -> valueOrDash(rs.getString("before_value")) + " | "
                        + valueOrDash(rs.getString("after_value")))
                .list();
    }

    private static String valueOrDash(String value) {
        return value == null ? "-" : value;
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.forEach(item -> names.add(item.get("name").asString()));
        return names;
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
