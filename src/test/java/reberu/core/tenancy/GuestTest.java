package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import reberu.core.tenancy.HomeScreens.HomeNext.Screen;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GuestTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    HomeScreens homeScreens;

    private UUID orgAdmin;
    private UUID teamAdmin;
    private UUID member;
    private UUID guest;
    private String orgId;
    private String forest;
    private String sea;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : InviteLinkTest.TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        orgAdmin = newAccount("orgadmin@example.com");
        teamAdmin = newAccount("teamadmin@example.com");
        member = newAccount("haneul@example.com");
        guest = newAccount("saebom@example.com");
        orgId = createOrg(orgAdmin, "새벽 서재");
        forest = createTeam("숲 팀", "PUBLIC");
        sea = createTeam("바다 팀", "PRIVATE");
        insertOrgMembership(member, "MEMBER", "ACTIVE", null);
        insertOrgMembership(teamAdmin, "MEMBER", "ACTIVE", null);
        insertTeamMembership(forest, teamAdmin, "TEAM_ADMIN", "ACTIVE", null);
        insertTeamMembership(forest, member, "TEAM_MEMBER", "ACTIVE", null);
        Instant until = Instant.now().plus(Duration.ofDays(30));
        insertOrgMembership(guest, "GUEST", "ACTIVE", until);
        insertTeamMembership(forest, guest, "TEAM_MEMBER", "ACTIVE", until);
    }

    @Test
    @DisplayName("T-GUEST-SCOPE: 손님은 다른 팀·공개 팀 목록·조직 구성원 목록·조직 기록 404, 관리자 지정 400, 만료 시각 직후 404")
    void guestStaysInsideInvitedTeam() throws Exception {
        assertThat(perform(get("/api/teams/" + forest), guest).getStatus()).isEqualTo(200);
        assertThat(perform(get("/api/teams/" + sea), guest).getStatus()).isEqualTo(404);
        assertThat(perform(get("/api/orgs/" + orgId + "/teams"), guest).getStatus()).isEqualTo(404);
        assertThat(perform(get("/api/orgs/" + orgId + "/members"), guest).getStatus()).isEqualTo(404);
        assertThat(perform(get("/api/orgs/" + orgId + "/activity"), guest).getStatus()).isEqualTo(404);
        assertThat(perform(post(members(guest) + "/grant-org-admin"), orgAdmin).getStatus()).isEqualTo(400);
        assertThat(orgMembership(guest)).isEqualTo("GUEST ACTIVE false");

        setExpiry(guest, forest, Instant.now().truncatedTo(ChronoUnit.MICROS));

        assertThat(perform(get("/api/teams/" + forest), guest).getStatus()).isEqualTo(404);
        assertThat(perform(get("/api/orgs/" + orgId), guest).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("TEN-GUEST-12: 첫 화면 판정·목록은 만료 시각이 지난 손님 조직·팀을 뺌, 다른 조직 구성원 멤버십은 그대로")
    void homeSkipsExpiredGuest() throws Exception {
        JsonNode before = json(perform(get("/api/me/home"), guest), 200);
        assertThat(before.get("next").get("screen").asString()).isEqualTo("TEAM_HOME");
        assertThat(before.get("orgs")).hasSize(1);
        assertThat(before.get("orgs").get(0).get("teams")).hasSize(1);
        assertThat(homeScreens.next(guest).screen()).isEqualTo(Screen.TEAM_HOME);

        insertTeamMembership(sea, guest, "VIEWER", "ACTIVE", Instant.now().minusSeconds(1));
        JsonNode partial = json(perform(get("/api/me/home"), guest), 200);
        assertThat(partial.get("orgs").get(0).get("teams")).hasSize(1);
        assertThat(partial.get("next").get("teamId").asString()).isEqualTo(forest);
        assertThat(homeScreens.next(guest).teamId().toString()).isEqualTo(forest);

        setExpiry(guest, forest, Instant.now().minusSeconds(1));

        JsonNode after = json(perform(get("/api/me/home"), guest), 200);
        assertThat(after.get("next").get("screen").asString()).isEqualTo("START");
        assertThat(after.get("orgs")).isEmpty();
        assertThat(homeScreens.next(guest).screen()).isEqualTo(Screen.START);

        UUID other = newAccount("other@example.com");
        String otherOrg = createOrg(other, "다른 서재");
        String otherTeam = jdbc.sql("SELECT id FROM team WHERE org_id = :org").param("org", otherOrg)
                .query(String.class).single();
        insertOrgMembership(otherOrg, guest, "MEMBER", "ACTIVE", null);
        insertTeamMembership(otherTeam, guest, "TEAM_MEMBER", "ACTIVE", null);

        JsonNode mixed = json(perform(get("/api/me/home"), guest), 200);
        assertThat(mixed.get("orgs")).hasSize(1);
        assertThat(mixed.get("orgs").get(0).get("orgId").asString()).isEqualTo(otherOrg);
        assertThat(mixed.get("next").get("teamId").asString()).isEqualTo(otherTeam);
        assertThat(homeScreens.next(guest).teamId().toString()).isEqualTo(otherTeam);
    }

    @Test
    @DisplayName("TEN-GUEST-09: 연장은 지금 + days, 조직 만료 시각은 끝나지 않은 팀에서 다시 계산, 줄어드는 연장 409, 범위 밖 days·정지된 손님 400, 팀 기록")
    void extendsGuest() throws Exception {
        Instant start = Instant.now();
        JsonNode extended = json(extend(guest, forest, 60, orgAdmin), 200);
        Instant teamUntil = Instant.parse(extended.get("teamExpiresAt").asString());
        assertThat(teamUntil).isCloseTo(start.plus(Duration.ofDays(60)), within(10, ChronoUnit.SECONDS));
        assertThat(extended.get("orgExpiresAt").asString()).isEqualTo(extended.get("teamExpiresAt").asString());
        assertThat(teamExpiry(forest, guest)).isEqualTo(teamUntil);
        assertThat(orgExpiry(guest)).isEqualTo(teamUntil);

        JsonNode repeated = json(extend(guest, forest, 60, orgAdmin), 200);
        assertThat(Instant.parse(repeated.get("teamExpiresAt").asString()))
                .isCloseTo(teamUntil, within(10, ChronoUnit.SECONDS));
        Instant current = teamExpiry(forest, guest);
        assertThat(extend(guest, forest, 30, orgAdmin).getStatus()).isEqualTo(409);
        assertThat(teamExpiry(forest, guest)).isEqualTo(current);

        assertThat(extend(guest, forest, 0, orgAdmin).getStatus()).isEqualTo(400);
        assertThat(extend(guest, forest, 91, orgAdmin).getStatus()).isEqualTo(400);
        jdbc.sql("UPDATE organization SET guest_default_days = 30, guest_max_days = 45 WHERE id = :org")
                .param("org", orgId).update();
        JsonNode tooLong = json(extend(guest, forest, 46, orgAdmin), 400);
        assertThat(tooLong.toString()).contains("days");
        assertThat(perform(post(members(guest) + "/extend-guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":\"" + forest + "\"}"), orgAdmin).getStatus()).isEqualTo(400);

        assertThat(extend(member, forest, 30, orgAdmin).getStatus()).isEqualTo(400);
        UUID suspended = newAccount("suspended@example.com");
        Instant suspendedUntil = Instant.now().plus(Duration.ofDays(5));
        insertOrgMembership(suspended, "GUEST", "SUSPENDED", suspendedUntil);
        insertTeamMembership(forest, suspended, "TEAM_MEMBER", "EXPIRED", Instant.now().minus(Duration.ofDays(1)));
        assertThat(extend(suspended, forest, 30, orgAdmin).getStatus()).isEqualTo(400);
        assertThat(teamMembership(forest, suspended)).isEqualTo("EXPIRED TEAM_MEMBER");
        assertThat(orgMembership(suspended)).isEqualTo("GUEST SUSPENDED false");
        assertThat(extend(guest, sea, 30, orgAdmin).getStatus()).isEqualTo(404);
        assertThat(extend(guest, UUID.randomUUID().toString(), 30, orgAdmin).getStatus()).isEqualTo(404);
        assertThat(teamExpiry(forest, guest)).isEqualTo(current);

        jdbc.sql("UPDATE organization SET guest_max_days = 90 WHERE id = :org").param("org", orgId).update();
        Instant stale = Instant.now().plus(Duration.ofDays(80));
        insertTeamMembership(sea, guest, "VIEWER", "LEFT", stale);
        jdbc.sql("UPDATE org_membership SET expires_at = :at WHERE org_id = :org AND account_id = :account")
                .param("at", LocalDateTime.ofInstant(stale.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC))
                .param("org", orgId).param("account", guest.toString()).update();
        JsonNode recomputed = json(extend(guest, forest, 70, orgAdmin), 200);
        assertThat(recomputed.get("orgExpiresAt").asString()).isEqualTo(recomputed.get("teamExpiresAt").asString());
        assertThat(orgExpiry(guest)).isEqualTo(teamExpiry(forest, guest)).isBefore(stale);
        assertThat(logs("team_activity_log", "GUEST_EXTENDED")).hasSize(3)
                .allSatisfy(row -> assertThat(row).startsWith(orgAdmin + " " + guest + " "));
        assertThat(logs("org_activity_log", "GUEST_EXTENDED")).isEmpty();
    }

    @Test
    @DisplayName("TEN-GUEST-09: 만료된 손님은 연장하면 그 팀과 조직이 ACTIVE, 정지된 팀은 정지 그대로, 보관된 팀은 409 READ_ONLY")
    void extendRevivesExpiredGuest() throws Exception {
        Instant past = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
        insertTeamMembership(sea, guest, "VIEWER", "SUSPENDED", past.plus(Duration.ofDays(10)));
        jdbc.sql("UPDATE team_membership SET state = 'EXPIRED', expires_at = :past WHERE team_id = :team AND account_id = :account")
                .param("past", LocalDateTime.ofInstant(past, ZoneOffset.UTC)).param("team", forest)
                .param("account", guest.toString()).update();
        jdbc.sql("UPDATE org_membership SET state = 'EXPIRED', expires_at = :past WHERE org_id = :org AND account_id = :account")
                .param("past", LocalDateTime.ofInstant(past, ZoneOffset.UTC)).param("org", orgId)
                .param("account", guest.toString()).update();
        assertThat(perform(get("/api/teams/" + forest), guest).getStatus()).isEqualTo(404);

        JsonNode revived = json(extend(guest, forest, 14, orgAdmin), 200);

        assertThat(teamMembership(forest, guest)).isEqualTo("ACTIVE TEAM_MEMBER");
        assertThat(orgMembership(guest)).isEqualTo("GUEST ACTIVE false");
        assertThat(orgExpiry(guest)).isEqualTo(Instant.parse(revived.get("teamExpiresAt").asString()));
        assertThat(perform(get("/api/teams/" + forest), guest).getStatus()).isEqualTo(200);
        assertThat(logs("org_activity_log", "GUEST_EXTENDED"))
                .containsExactly(orgAdmin + " " + guest + " EXPIRED ACTIVE");

        json(extend(guest, sea, 30, orgAdmin), 200);
        assertThat(teamMembership(sea, guest)).isEqualTo("SUSPENDED VIEWER");
        assertThat(orgExpiry(guest)).isEqualTo(teamExpiry(sea, guest));
        json(extend(guest, forest, 20, orgAdmin), 200);
        assertThat(orgExpiry(guest)).isEqualTo(teamExpiry(sea, guest));

        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :team").param("team", sea).update();
        assertThat(json(extend(guest, sea, 60, orgAdmin), 409).get("code").asString()).isEqualTo("READ_ONLY");
    }

    @Test
    @DisplayName("TEN-GUEST-07·10: 구성원으로 바꾸면 종류 MEMBER·만료 시각 없음, 이미 지난 팀은 EXPIRED, 반복 409, 만료된 손님 400")
    void convertsGuestToMember() throws Exception {
        Instant past = Instant.now().minusSeconds(60);
        insertTeamMembership(sea, guest, "VIEWER", "ACTIVE", past);
        assertThat(perform(get("/api/orgs/" + orgId + "/teams"), guest).getStatus()).isEqualTo(404);

        assertThat(perform(post(members(guest) + "/convert-to-member"), orgAdmin).getStatus()).isEqualTo(204);

        assertThat(orgMembership(guest)).isEqualTo("MEMBER ACTIVE false");
        assertThat(orgExpiry(guest)).isNull();
        assertThat(teamMembership(forest, guest)).isEqualTo("ACTIVE TEAM_MEMBER");
        assertThat(teamExpiry(forest, guest)).isNull();
        assertThat(teamMembership(sea, guest)).isEqualTo("EXPIRED VIEWER");
        assertThat(perform(get("/api/orgs/" + orgId + "/teams"), guest).getStatus()).isEqualTo(200);
        assertThat(logs("org_activity_log", "GUEST_CONVERTED")).containsExactly(orgAdmin + " " + guest + " GUEST MEMBER");
        assertThat(logs("team_activity_log", "GUEST_EXPIRED")).containsExactly("null " + guest + " ACTIVE EXPIRED");

        assertThat(json(perform(post(members(guest) + "/convert-to-member"), orgAdmin), 409).get("code").asString())
                .isEqualTo("ALREADY_PROCESSED");
        assertThat(perform(post(members(member) + "/convert-to-member"), orgAdmin).getStatus()).isEqualTo(409);

        UUID expired = newAccount("expired@example.com");
        insertOrgMembership(expired, "GUEST", "ACTIVE", past);
        insertTeamMembership(forest, expired, "TEAM_MEMBER", "ACTIVE", past);
        assertThat(perform(post(members(expired) + "/convert-to-member"), orgAdmin).getStatus()).isEqualTo(400);
        jdbc.sql("UPDATE org_membership SET state = 'EXPIRED' WHERE account_id = :account")
                .param("account", expired.toString()).update();
        assertThat(perform(post(members(expired) + "/convert-to-member"), orgAdmin).getStatus()).isEqualTo(400);
        assertThat(orgMembership(expired)).isEqualTo("GUEST EXPIRED false");

        UUID suspended = newAccount("suspended@example.com");
        Instant until = Instant.now().plus(Duration.ofDays(5));
        insertOrgMembership(suspended, "GUEST", "SUSPENDED", until);
        insertTeamMembership(forest, suspended, "VIEWER", "SUSPENDED", until);
        assertThat(perform(post(members(suspended) + "/convert-to-member"), orgAdmin).getStatus()).isEqualTo(204);
        assertThat(orgMembership(suspended)).isEqualTo("MEMBER SUSPENDED false");
        assertThat(teamMembership(forest, suspended)).isEqualTo("SUSPENDED VIEWER");
        assertThat(teamExpiry(forest, suspended)).isNull();
    }

    @Test
    @DisplayName("T-ISO-ORG: 연장·바꾸기는 팀 관리자·구성원 403, 손님 본인·다른 조직 관리자 404, 최근 인증 없으면 401, 아무것도 안 바뀜")
    void onlyOrgAdminsManageGuests() throws Exception {
        UUID stranger = newAccount("stranger@example.com");
        createOrg(stranger, "다른 서재");
        Instant until = teamExpiry(forest, guest);

        for (UUID actor : List.of(teamAdmin, member)) {
            assertThat(extend(guest, forest, 60, actor).getStatus()).isEqualTo(403);
            assertThat(perform(post(members(guest) + "/convert-to-member"), actor).getStatus()).isEqualTo(403);
        }
        for (UUID actor : List.of(guest, stranger)) {
            assertThat(extend(guest, forest, 60, actor).getStatus()).isEqualTo(404);
            assertThat(perform(post(members(guest) + "/convert-to-member"), actor).getStatus()).isEqualTo(404);
        }
        MockHttpServletResponse stale = mockMvc.perform(post(members(guest) + "/convert-to-member")
                .with(authentication(new AccountAuthentication(orgAdmin, Instant.now().minus(Duration.ofHours(2)))))
                .with(csrfToken())).andReturn().getResponse();
        assertThat(stale.getStatus()).isEqualTo(401);

        assertThat(orgMembership(guest)).isEqualTo("GUEST ACTIVE false");
        assertThat(teamExpiry(forest, guest)).isEqualTo(until);
        assertThat(logs("team_activity_log", "GUEST_EXTENDED")).isEmpty();
        assertThat(logs("org_activity_log", "GUEST_CONVERTED")).isEmpty();
    }

    private String members(UUID account) {
        return "/api/orgs/" + orgId + "/members/" + account;
    }

    private MockHttpServletResponse extend(UUID target, String team, int days, UUID actor) throws Exception {
        return perform(post(members(target) + "/extend-guest").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("teamId", team, "days", days))), actor);
    }

    private void setExpiry(UUID account, String team, Instant at) {
        LocalDateTime value = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        jdbc.sql("UPDATE team_membership SET expires_at = :at WHERE team_id = :team AND account_id = :account")
                .param("at", value).param("team", team).param("account", account.toString()).update();
        jdbc.sql("UPDATE org_membership SET expires_at = :at WHERE org_id = :org AND account_id = :account")
                .param("at", value).param("org", orgId).param("account", account.toString()).update();
    }

    private String orgMembership(UUID account) {
        return jdbc.sql("SELECT kind, state, org_admin FROM org_membership WHERE org_id = :org AND account_id = :account")
                .param("org", orgId).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("kind") + " " + rs.getString("state") + " "
                        + rs.getBoolean("org_admin"))
                .single();
    }

    private String teamMembership(String team, UUID account) {
        return jdbc.sql("SELECT state, role FROM team_membership WHERE team_id = :team AND account_id = :account")
                .param("team", team).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + " " + rs.getString("role"))
                .single();
    }

    private Instant orgExpiry(UUID account) {
        return jdbc.sql("SELECT expires_at FROM org_membership WHERE org_id = :org AND account_id = :account")
                .param("org", orgId).param("account", account.toString())
                .query((rs, rowNum) -> instant(rs.getObject("expires_at", LocalDateTime.class)))
                .list().get(0);
    }

    private Instant teamExpiry(String team, UUID account) {
        return jdbc.sql("SELECT expires_at FROM team_membership WHERE team_id = :team AND account_id = :account")
                .param("team", team).param("account", account.toString())
                .query((rs, rowNum) -> instant(rs.getObject("expires_at", LocalDateTime.class)))
                .list().get(0);
    }

    private List<String> logs(String table, String action) {
        return jdbc.sql("SELECT actor_id, target_id, before_value, after_value FROM " + table
                        + " WHERE action = :action ORDER BY occurred_at, id")
                .param("action", action)
                .query((rs, rowNum) -> rs.getString("actor_id") + " " + rs.getString("target_id") + " "
                        + rs.getString("before_value") + " " + rs.getString("after_value"))
                .list();
    }

    private void insertOrgMembership(UUID account, String kind, String state, Instant expiresAt) {
        insertOrgMembership(orgId, account, kind, state, expiresAt);
    }

    private void insertOrgMembership(String org, UUID account, String kind, String state, Instant expiresAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version, admission_review)
                        VALUES (:id, :org, :account, :kind, FALSE, :state, 'INVITATION', :expiresAt, :now, :now, 0,
                            FALSE)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", org)
                .param("account", account.toString())
                .param("kind", kind)
                .param("state", state)
                .param("expiresAt", timestamp(expiresAt))
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String team, UUID account, String role, String state, Instant expiresAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, expires_at,
                            created_at, updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'INVITATION', :expiresAt, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", team)
                .param("account", account.toString())
                .param("role", role)
                .param("state", state)
                .param("expiresAt", timestamp(expiresAt))
                .param("now", now)
                .update();
    }

    private String createTeam(String name, String visibility) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", visibility);
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", orgId).query(String.class).single());
        body.put("firstAdminAccountId", orgAdmin.toString());
        return json(perform(post("/api/orgs/" + orgId + "/teams")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), orgAdmin), 201)
                .get("teamId").asString();
    }

    private String createOrg(UUID actor, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("timeZone", "Asia/Seoul");
        body.put("firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"));
        return json(perform(post("/api/orgs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), actor), 201)
                .get("orgId").asString();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
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

    private static LocalDateTime timestamp(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

}
