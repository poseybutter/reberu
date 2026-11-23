package reberu.core.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import fakemodule.FakeModules;
import fakemodule.ext.FakeExtensionModule;
import fakemodule.org.FakeOrgModule;
import fakemodule.team.FakeTeamModule;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import reberu.core.access.ModuleSwitch;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeModules.class})
class ModuleAccessTest {

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
    ModuleSwitch modules;

    private UUID admin;
    private UUID outsider;
    private String orgId;
    private String teamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = newAccount("admin");
        outsider = newAccount("outsider");
        orgId = createOrg(admin, "새벽 서재");
        teamId = jdbc.sql("SELECT id FROM team WHERE org_id = :org").param("org", orgId).query(String.class).single();
    }

    @Test
    @DisplayName("T-MODULE-OFF·TEN-MOD-07: 팀에서 꺼진 BUNDLED 모듈 API는 없는 팀과 같은 404, 팀이 켜면 200, 조직이 거두면 다시 404")
    void teamModuleFollowsSwitches() throws Exception {
        String path = "/api/teams/" + teamId + "/mod/" + FakeTeamModule.KEY + "/ping";
        MockHttpServletResponse missing = call("/api/teams/" + UUID.randomUUID() + "/mod/fake/ping", admin);
        assertThat(missing.getStatus()).isEqualTo(404);

        MockHttpServletResponse off = call(path, admin);
        assertThat(off.getStatus()).isEqualTo(404);
        assertThat(code(off)).isEqualTo(code(missing)).isEqualTo("NOT_FOUND");

        setTeam(FakeTeamModule.KEY, true);
        MockHttpServletResponse on = call(path, admin);
        assertThat(on.getStatus()).as(on.getContentAsString()).isEqualTo(200);
        assertThat(json(on).get("teamId").asString()).isEqualTo(teamId);
        assertThat(call(path, outsider).getStatus()).isEqualTo(404);

        setOrg(FakeTeamModule.KEY, false, false);
        assertThat(code(call(path, admin))).isEqualTo("NOT_FOUND");

        setOrg(FakeTeamModule.KEY, true, false);
        assertThat(call(path, admin).getStatus()).isEqualTo(200);

        setTeam(FakeTeamModule.KEY, false);
        assertThat(code(call(path, admin))).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("TEN-MOD-07·TEN-ORG-04: EXTENSION 모듈은 조직이 허용하기 전에는 팀이 켜도 404")
    void extensionNeedsOrgAllowance() throws Exception {
        String path = "/api/teams/" + teamId + "/mod/" + FakeExtensionModule.KEY + "/ping";
        setTeam(FakeExtensionModule.KEY, true);
        assertThat(code(call(path, admin))).isEqualTo("NOT_FOUND");

        setOrg(FakeExtensionModule.KEY, true, false);
        assertThat(call(path, admin).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("T-MODULE-OFF: 조직 범위 모듈은 조직이 켰을 때만 200")
    void orgModuleFollowsSwitch() throws Exception {
        String path = "/api/orgs/" + orgId + "/mod/" + FakeOrgModule.KEY + "/ping";
        assertThat(code(call(path, admin))).isEqualTo("NOT_FOUND");

        setOrg(FakeOrgModule.KEY, false, true);
        MockHttpServletResponse on = call(path, admin);
        assertThat(on.getStatus()).as(on.getContentAsString()).isEqualTo(200);
        assertThat(call(path, outsider).getStatus()).isEqualTo(404);

        setOrg(FakeOrgModule.KEY, false, false);
        assertThat(code(call(path, admin))).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("ARCH-MOD-04: 설치되지 않은 key와 범위가 다른 key는 켜짐 기록이 있어도 꺼진 것")
    void uninstalledOrMismatchedKeyIsOff() {
        setOrg("gone", true, true);
        setTeam("gone", true);
        setTeam(FakeOrgModule.KEY, true);
        setOrg(FakeTeamModule.KEY, true, true);

        assertThat(modules.enabledInTeam(UUID.fromString(teamId), "gone")).isFalse();
        assertThat(modules.enabledInOrg(UUID.fromString(orgId), "gone")).isFalse();
        assertThat(modules.enabledInTeam(UUID.fromString(teamId), FakeOrgModule.KEY)).isFalse();
        assertThat(modules.enabledInOrg(UUID.fromString(orgId), FakeTeamModule.KEY)).isFalse();
        assertThat(modules.enabledInTeam(UUID.randomUUID(), FakeTeamModule.KEY)).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM team_module_state").query(Integer.class).single()).isEqualTo(2);
    }

    private void setOrg(String key, boolean allowed, boolean enabled) {
        jdbc.sql("DELETE FROM org_module_state WHERE org_id = :org AND module_key = :key")
                .param("org", orgId).param("key", key).update();
        jdbc.sql("""
                        INSERT INTO org_module_state (id, org_id, module_key, allowed, enabled, changed_by, changed_at)
                        VALUES (:id, :org, :key, :allowed, :enabled, NULL, :now)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("key", key)
                .param("allowed", allowed)
                .param("enabled", enabled)
                .param("now", LocalDateTime.now(ZoneOffset.UTC))
                .update();
    }

    private void setTeam(String key, boolean enabled) {
        jdbc.sql("DELETE FROM team_module_state WHERE team_id = :team AND module_key = :key")
                .param("team", teamId).param("key", key).update();
        jdbc.sql("""
                        INSERT INTO team_module_state (id, team_id, module_key, enabled, changed_by, changed_at)
                        VALUES (:id, :team, :key, :enabled, NULL, :now)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", teamId)
                .param("key", key)
                .param("enabled", enabled)
                .param("now", LocalDateTime.now(ZoneOffset.UTC))
                .update();
    }

    private MockHttpServletResponse call(String path, UUID actor) throws Exception {
        return mockMvc.perform(get(path).with(authentication(new AccountAuthentication(actor, Instant.now()))))
                .andReturn().getResponse();
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return json(response).get("code").asString();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString());
    }

    private String createOrg(UUID actor, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("timeZone", "Asia/Seoul");
        body.put("firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"));
        MockHttpServletResponse response = perform(post("/api/orgs"), actor, body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return json(response).get("orgId").asString();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor,
            Map<String, Object> body) throws Exception {
        return mockMvc.perform(request.header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body))
                .with(authentication(new AccountAuthentication(actor, Instant.now()))).with(csrfToken()))
                .andReturn().getResponse();
    }

    private UUID newAccount(String name) {
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(),
                name + "@example.com", name, null, null), "v1", "v1", Instant.now());
    }

}
