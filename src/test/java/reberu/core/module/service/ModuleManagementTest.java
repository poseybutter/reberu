package reberu.core.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static reberu.TestCsrf.csrfToken;

import fakemodule.FakeModules;
import fakemodule.addon.FakeAddonModule;
import fakemodule.ext.FakeExtensionModule;
import fakemodule.org.FakeOrgModule;
import fakemodule.orgaddon.FakeOrgAddonModule;
import fakemodule.preset.FakePresetModule;
import fakemodule.team.FakeTeamModule;
import java.time.Clock;
import java.time.Duration;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.access.ModuleSwitch;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.audit.ActivityLogs;
import reberu.core.auth.security.AccountAuthentication;
import reberu.core.module.ModuleStates;
import reberu.core.module.OrgModuleChanged;
import reberu.core.module.TeamModuleChanged;
import reberu.core.support.ReberuException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import({TestcontainersConfiguration.class, FakeModules.class})
class ModuleManagementTest {

    private static final List<String> TABLES = List.of("team_module_state", "org_module_state", "org_activity_log",
            "team_activity_log", "team_task_label", "team_custom_field", "team_membership", "team", "job_type",
            "org_membership", "organization", "api_idempotency_key", "account_consent", "account");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    ModuleStates moduleStates;

    @Autowired
    ActivityLogs activityLogs;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    ApplicationEvents events;

    @Autowired
    ModuleService moduleService;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID admin;
    private UUID member;
    private UUID outsider;
    private String orgId;
    private String teamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = newAccount("admin");
        member = newAccount("member");
        outsider = newAccount("outsider");
        orgId = created(perform(post("/api/orgs"), admin, Map.of("name", "새벽 서재", "visibility", "PRIVATE",
                "timeZone", "Asia/Seoul", "firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"))))
                .get("orgId").asString();
        teamId = jdbc.sql("SELECT id FROM team WHERE org_id = :org").param("org", orgId).query(String.class).single();
        insertOrgMembership(member);
        insertTeamMembership(teamId, member, "TEAM_MEMBER");
    }

    @Test
    @DisplayName("ARCH-TEST-07·PRD-MVP-12: 가짜 모듈 등록 → 허용 → 켜기 → 끄기 → 제거가 API·목록·권한에 그대로 반영")
    void followsModuleLifecycle() throws Exception {
        JsonNode installed = json(call(get("/api/orgs/" + orgId + "/modules"), admin));
        assertThat(keys(installed)).containsExactly("fake", "fake-addon", "fake-ext", "fake-org", "fake-org-addon",
                "fake-preset");
        JsonNode extension = find(installed, FakeExtensionModule.KEY);
        assertThat(extension.get("name").asString()).isEqualTo("가짜 확장 모듈");
        assertThat(extension.get("scope").asString()).isEqualTo("TEAM");
        assertThat(extension.get("source").asString()).isEqualTo("EXTENSION");
        assertThat(extension.get("allowed").asBoolean()).isFalse();
        assertThat(extension.get("enabled").isNull()).isTrue();
        assertThat(find(installed, FakeOrgModule.KEY).get("allowed").isNull()).isTrue();
        assertThat(find(installed, FakeAddonModule.KEY).get("requires").get(0).asString()).isEqualTo("fake");
        assertThat(keys(json(call(get("/api/teams/" + teamId + "/modules"), admin))))
                .doesNotContain(FakeExtensionModule.KEY);

        String ping = "/api/teams/" + teamId + "/mod/" + FakeExtensionModule.KEY + "/ping";
        assertThat(call(put(orgPath(FakeExtensionModule.KEY, "allowed")), admin, Map.of("allowed", true))
                .getStatus()).isEqualTo(204);
        assertThat(code(call(put(orgPath(FakeExtensionModule.KEY, "allowed")), admin, Map.of("allowed", true))))
                .isEqualTo("ALREADY_PROCESSED");
        assertThat(code(call(get(ping), admin))).isEqualTo("NOT_FOUND");

        assertThat(call(put(teamPath(FakeExtensionModule.KEY)), admin, Map.of("enabled", true)).getStatus())
                .isEqualTo(204);
        assertThat(call(get(ping), member).getStatus()).isEqualTo(200);
        assertThat(texts(json(call(get("/api/teams/" + teamId), member)).get("modules")))
                .containsExactly(FakeExtensionModule.KEY, FakePresetModule.KEY);
        assertThat(find(json(call(get("/api/orgs/" + orgId + "/modules"), admin)), FakeExtensionModule.KEY)
                .get("enabledTeamCount").asInt()).isEqualTo(1);

        assertThat(call(put(teamPath(FakeExtensionModule.KEY)), admin, Map.of("enabled", false)).getStatus())
                .isEqualTo(204);
        assertThat(code(call(get(ping), member))).isEqualTo("NOT_FOUND");
        assertThat(teamLogs()).containsExactly("MODULE_ENABLED fake-preset", "MODULE_ENABLED fake-ext",
                "MODULE_DISABLED fake-ext");
        assertThat(orgLogs()).containsExactly("MODULE_ALLOWED fake-ext");

        setTeam(FakeTeamModule.KEY, true);
        ModuleRegistry withoutFakes = new ModuleRegistry(List.of());
        ModuleService removed = new ModuleService(withoutFakes, new ModuleStateStore(jdbc), activityLogs, publisher,
                Clock.systemUTC());
        ModuleSwitch removedSwitch = new JdbcModuleSwitch(withoutFakes, jdbc);
        assertThat(removed.listOrg(UUID.fromString(orgId))).isEmpty();
        assertThat(removed.enabledTeamModules(UUID.fromString(teamId))).isEmpty();
        assertThat(removedSwitch.enabledInTeam(UUID.fromString(teamId), FakeTeamModule.KEY)).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM team_module_state WHERE team_id = :team AND enabled = :on")
                .param("team", teamId).param("on", true).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("TEN-MOD-11·OQ-41: 새 팀은 직무 유형 목록, 없으면 기본 켜짐 모듈을 켜고, 허용되지 않았거나 필요한 모듈이 없으면 건너뜀")
    void enablesModulesForNewTeams() {
        assertThat(rows(UUID.fromString(teamId))).containsExactly(FakePresetModule.KEY);
        assertThat(teamLogs()).containsExactly("MODULE_ENABLED fake-preset");

        UUID ordered = UUID.randomUUID();
        moduleStates.enableForNewTeam(UUID.fromString(orgId), ordered, List.of(FakeAddonModule.KEY,
                FakeTeamModule.KEY, FakeExtensionModule.KEY, FakeOrgModule.KEY, "nowhere"), admin, Instant.now());
        assertThat(rows(ordered)).containsExactlyInAnyOrder(FakeTeamModule.KEY, FakeAddonModule.KEY);

        UUID orphan = UUID.randomUUID();
        moduleStates.enableForNewTeam(UUID.fromString(orgId), orphan, List.of(FakeAddonModule.KEY), admin,
                Instant.now());
        assertThat(rows(orphan)).isEmpty();
    }

    @Test
    @DisplayName("TEN-MOD-04·10: 필요한 모듈이 꺼져 있으면 켜기 400, 함께 꺼질 모듈은 확인을 받고 함께 끄며 이벤트에 켜진 목록")
    void teamDependenciesNeedConfirmation() throws Exception {
        assertThat(field(call(put(teamPath(FakeAddonModule.KEY)), admin, Map.of("enabled", true))))
                .isEqualTo("enabled NOT_ALLOWED_VALUE");
        JsonNode before = json(call(get(teamPath(FakeAddonModule.KEY).replace("/enabled", "/impact")), admin));
        assertThat(texts(before.get("requiredFirst"))).containsExactly(FakeTeamModule.KEY);

        assertThat(call(put(teamPath(FakeTeamModule.KEY)), admin, Map.of("enabled", true)).getStatus())
                .isEqualTo(204);
        assertThat(call(put(teamPath(FakeAddonModule.KEY)), admin, Map.of("enabled", true)).getStatus())
                .isEqualTo(204);
        assertThat(code(call(put(teamPath(FakeAddonModule.KEY)), admin, Map.of("enabled", true))))
                .isEqualTo("ALREADY_PROCESSED");
        JsonNode impact = json(call(get(teamPath(FakeTeamModule.KEY).replace("/enabled", "/impact")), admin));
        assertThat(texts(impact.get("cascade"))).containsExactly(FakeAddonModule.KEY);
        assertThat(texts(impact.get("requiredFirst"))).isEmpty();

        assertThat(field(call(put(teamPath(FakeTeamModule.KEY)), admin, Map.of("enabled", false))))
                .isEqualTo("confirmCascade REQUIRED");
        assertThat(rows(UUID.fromString(teamId))).contains(FakeTeamModule.KEY, FakeAddonModule.KEY);
        events.clear();
        assertThat(call(put(teamPath(FakeTeamModule.KEY)), admin, Map.of("enabled", false, "confirmCascade", true))
                .getStatus()).isEqualTo(204);
        assertThat(rows(UUID.fromString(teamId))).containsExactly(FakePresetModule.KEY);
        assertThat(code(call(get("/api/teams/" + teamId + "/mod/fake-addon/ping"), admin))).isEqualTo("NOT_FOUND");
        assertThat(teamLogs()).hasSize(5).contains("MODULE_DISABLED fake", "MODULE_DISABLED fake-addon");
        assertThat(events.stream(TeamModuleChanged.class).toList()).containsExactly(new TeamModuleChanged(
                UUID.fromString(orgId), UUID.fromString(teamId), List.of(FakePresetModule.KEY)));
    }

    @Test
    @DisplayName("TEN-MOD-09: 허용을 거두면 모든 팀에서 그 모듈과 의존 모듈이 꺼지고, 영향 미리 보기는 꺼질 팀 수와 함께 꺼질 모듈")
    void revokingTurnsOffEveryTeam() throws Exception {
        String second = created(perform(post("/api/orgs/" + orgId + "/teams"), admin, team("둘째 팀")))
                .get("teamId").asString();
        for (String team : List.of(teamId, second)) {
            setTeam(team, FakeTeamModule.KEY, true);
        }
        setTeam(second, FakeAddonModule.KEY, true);

        JsonNode impact = json(call(get(orgPath(FakeTeamModule.KEY, "impact")), admin));
        assertThat(impact.get("affectedTeamCount").asInt()).isEqualTo(2);
        assertThat(texts(impact.get("cascade"))).containsExactly(FakeAddonModule.KEY);

        assertThat(call(put(orgPath(FakeTeamModule.KEY, "allowed")), admin, Map.of("allowed", false)).getStatus())
                .isEqualTo(204);
        assertThat(rows(UUID.fromString(teamId))).containsExactly(FakePresetModule.KEY);
        assertThat(rows(UUID.fromString(second))).containsExactly(FakePresetModule.KEY);
        JsonNode fake = find(json(call(get("/api/orgs/" + orgId + "/modules"), admin)), FakeTeamModule.KEY);
        assertThat(fake.get("allowed").asBoolean()).isFalse();
        assertThat(fake.get("enabledTeamCount").asInt()).isZero();
        assertThat(keys(json(call(get("/api/teams/" + teamId + "/modules"), admin)))).doesNotContain("fake");
        assertThat(code(call(put(teamPath(FakeTeamModule.KEY)), admin, Map.of("enabled", true))))
                .isEqualTo("NOT_FOUND");
        assertThat(orgLogs()).containsExactly("MODULE_REVOKED fake");
    }

    @Test
    @DisplayName("TEN-MOD-04·10: 조직 범위 모듈은 필요한 모듈이 켜져야 켜지고, 끄면 의존 모듈도 함께 꺼짐")
    void orgModulesFollowDependencies() throws Exception {
        assertThat(field(call(put(orgPath(FakeOrgAddonModule.KEY, "enabled")), admin, Map.of("enabled", true))))
                .isEqualTo("enabled NOT_ALLOWED_VALUE");
        assertThat(call(put(orgPath(FakeOrgModule.KEY, "enabled")), admin, Map.of("enabled", true)).getStatus())
                .isEqualTo(204);
        assertThat(call(put(orgPath(FakeOrgAddonModule.KEY, "enabled")), admin, Map.of("enabled", true))
                .getStatus()).isEqualTo(204);
        assertThat(call(get("/api/orgs/" + orgId + "/mod/fake-org/ping"), member).getStatus()).isEqualTo(200);
        JsonNode impact = json(call(get(orgPath(FakeOrgModule.KEY, "impact")), admin));
        assertThat(impact.get("affectedTeamCount").isNull()).isTrue();
        assertThat(texts(impact.get("cascade"))).containsExactly(FakeOrgAddonModule.KEY);

        events.clear();
        assertThat(call(put(orgPath(FakeOrgModule.KEY, "enabled")), admin, Map.of("enabled", false)).getStatus())
                .isEqualTo(204);
        JsonNode list = json(call(get("/api/orgs/" + orgId + "/modules"), admin));
        assertThat(find(list, FakeOrgModule.KEY).get("enabled").asBoolean()).isFalse();
        assertThat(find(list, FakeOrgAddonModule.KEY).get("enabled").asBoolean()).isFalse();
        assertThat(code(call(get("/api/orgs/" + orgId + "/mod/fake-org/ping"), member))).isEqualTo("NOT_FOUND");
        assertThat(events.stream(OrgModuleChanged.class).toList())
                .containsExactly(new OrgModuleChanged(UUID.fromString(orgId), List.of()));
        assertThat(orgLogs()).containsExactlyInAnyOrder("MODULE_ENABLED fake-org", "MODULE_ENABLED fake-org-addon",
                "MODULE_DISABLED fake-org", "MODULE_DISABLED fake-org-addon");
    }

    @Test
    @DisplayName("API-MOD·TEN-PERM: 범위가 맞지 않거나 설치되지 않은 key는 404, 조직 API는 조직 관리자·최근 인증, 팀 API는 팀 관리자, 보관된 팀은 409")
    void guardsScopesAndPermissions() throws Exception {
        assertThat(code(call(put(orgPath(FakeOrgModule.KEY, "allowed")), admin, Map.of("allowed", true))))
                .isEqualTo("NOT_FOUND");
        assertThat(code(call(put(orgPath(FakeTeamModule.KEY, "enabled")), admin, Map.of("enabled", true))))
                .isEqualTo("NOT_FOUND");
        assertThat(code(call(put(teamPath(FakeOrgModule.KEY)), admin, Map.of("enabled", true))))
                .isEqualTo("NOT_FOUND");
        assertThat(code(call(get(orgPath("nowhere", "impact")), admin))).isEqualTo("NOT_FOUND");
        assertThat(code(call(get(teamPath("nowhere").replace("/enabled", "/impact")), admin)))
                .isEqualTo("NOT_FOUND");
        assertThat(field(call(put(teamPath(FakeTeamModule.KEY)), admin, Map.of()))).isEqualTo("enabled REQUIRED");

        assertThat(code(call(get("/api/orgs/" + orgId + "/modules"), member))).isEqualTo("FORBIDDEN_ACTION");
        assertThat(code(call(get("/api/teams/" + teamId + "/modules"), member))).isEqualTo("FORBIDDEN_ACTION");
        assertThat(code(call(put(teamPath(FakeTeamModule.KEY)), member, Map.of("enabled", true))))
                .isEqualTo("FORBIDDEN_ACTION");
        assertThat(code(call(get("/api/teams/" + teamId + "/modules"), outsider))).isEqualTo("NOT_FOUND");
        assertThat(code(perform(put(orgPath(FakeExtensionModule.KEY, "allowed")), admin, Map.of("allowed", true),
                Instant.now().minus(Duration.ofHours(2))))).isEqualTo("REAUTH_REQUIRED");

        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :team").param("team", teamId).update();
        assertThat(code(call(put(teamPath(FakeTeamModule.KEY)), admin, Map.of("enabled", true))))
                .isEqualTo("READ_ONLY");
        assertThat(rows(UUID.fromString(teamId))).containsExactly(FakePresetModule.KEY);
    }

    @Test
    @DisplayName("TEN-CTX-09·TEN-MOD-09: 허용을 거두는 중이면 팀 켜기는 조직 행에서 기다렸다가 다시 확인해 404, 켜짐 기록 없음")
    void teamEnableWaitsForRevoke() throws Exception {
        UUID org = UUID.fromString(orgId);
        UUID team = UUID.fromString(teamId);
        Held revoke = hold(() -> moduleService.setAllowed(org, FakeTeamModule.KEY, false, admin));

        CompletableFuture<String> enable = startBlocked(() -> {
            try {
                moduleService.setTeamEnabled(org, team, FakeTeamModule.KEY, true, null, admin);
                return "ENABLED";
            }
            catch (ReberuException rejected) {
                return rejected.code().name();
            }
        }, "팀 켜기 조직 행 대기");
        revoke.finish();

        assertThat(enable.get(30, TimeUnit.SECONDS)).isEqualTo("NOT_FOUND");
        assertThat(rows(team)).containsExactly(FakePresetModule.KEY);
        assertThat(orgLogs()).containsExactly("MODULE_REVOKED fake");
    }

    private Held hold(Runnable action) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    action.run();
                    done.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
        holder.start();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        return new Held(holder, release);
    }

    private static <T> CompletableFuture<T> startBlocked(Supplier<T> task, String label) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            return task.get();
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).as(label + " 시작").isTrue();
        Thread.sleep(500);
        assertThat(future.isDone()).as(label).isFalse();
        return future;
    }

    private record Held(Thread holder, CountDownLatch release) {

        void finish() throws InterruptedException {
            release.countDown();
            holder.join();
        }

    }

    private String orgPath(String key, String action) {
        return "/api/orgs/" + orgId + "/modules/" + key + "/" + action;
    }

    private String teamPath(String key) {
        return "/api/teams/" + teamId + "/modules/" + key + "/enabled";
    }

    private List<String> rows(UUID team) {
        return jdbc.sql("SELECT module_key FROM team_module_state WHERE team_id = :team AND enabled = :on "
                        + "ORDER BY module_key")
                .param("team", team.toString()).param("on", true).query(String.class).list();
    }

    private List<String> teamLogs() {
        return logs("team_activity_log");
    }

    private List<String> orgLogs() {
        return logs("org_activity_log");
    }

    private List<String> logs(String table) {
        return jdbc.sql("SELECT action, after_value FROM " + table + " WHERE action LIKE 'MODULE_%' "
                        + "ORDER BY occurred_at, id")
                .query((rs, rowNum) -> rs.getString("action") + " " + rs.getString("after_value"))
                .list();
    }

    private void setTeam(String key, boolean enabled) {
        setTeam(teamId, key, enabled);
    }

    private void setTeam(String team, String key, boolean enabled) {
        jdbc.sql("DELETE FROM team_module_state WHERE team_id = :team AND module_key = :key")
                .param("team", team).param("key", key).update();
        jdbc.sql("""
                        INSERT INTO team_module_state (id, team_id, module_key, enabled, changed_by, changed_at)
                        VALUES (:id, :team, :key, :enabled, NULL, :now)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", team)
                .param("key", key)
                .param("enabled", enabled)
                .param("now", LocalDateTime.now(ZoneOffset.UTC))
                .update();
    }

    private void insertOrgMembership(UUID account) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version, admission_review)
                        VALUES (:id, :org, :account, 'MEMBER', FALSE, 'ACTIVE', 'INVITATION', NULL, :now, :now, 0,
                            FALSE)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", account.toString())
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String team, UUID account, String role) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, expires_at,
                            created_at, updated_at, version)
                        VALUES (:id, :team, :account, :role, 'ACTIVE', 'INVITATION', NULL, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", team)
                .param("account", account.toString())
                .param("role", role)
                .param("now", now)
                .update();
    }

    private Map<String, Object> team(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", orgId).query(String.class).single());
        body.put("firstAdminAccountId", admin.toString());
        return body;
    }

    private static List<String> keys(JsonNode array) {
        List<String> keys = new ArrayList<>();
        array.forEach(node -> keys.add(node.get("key").asString()));
        return keys;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private static JsonNode find(JsonNode array, String key) {
        for (JsonNode node : array) {
            if (node.get("key").asString().equals(key)) {
                return node;
            }
        }
        throw new AssertionError("모듈 없음: " + key);
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now()))))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, UUID actor, Map<String, Object> body)
            throws Exception {
        return perform(request, actor, body);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor,
            Map<String, Object> body) throws Exception {
        return perform(request, actor, body, Instant.now());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor,
            Map<String, Object> body, Instant authTime) throws Exception {
        return mockMvc.perform(request.header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body))
                .with(authentication(new AccountAuthentication(actor, authTime))).with(csrfToken()))
                .andReturn().getResponse();
    }

    private JsonNode created(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return json(response);
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return json(response).get("code").asString();
    }

    private String field(MockHttpServletResponse response) throws Exception {
        JsonNode body = json(response);
        assertThat(body.get("code").asString()).as(response.getContentAsString()).isEqualTo("INVALID_REQUEST");
        JsonNode first = body.get("fields").get(0);
        return first.get("field").asString() + " " + first.get("reason").asString();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString());
    }

    private UUID newAccount(String name) {
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(),
                name + "@example.com", name, null, null), "v1", "v1", Instant.now());
    }

}
