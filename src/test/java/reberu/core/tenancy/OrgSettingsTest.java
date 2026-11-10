package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrgSettingsTest {

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

    private UUID admin;
    private UUID member;
    private UUID guest;
    private UUID outsider;
    private String orgId;
    private String firstTeamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = newAccount("admin");
        member = newAccount("member");
        guest = newAccount("guest");
        outsider = newAccount("outsider");
        JsonNode created = json(createOrg(admin, "새벽 서재"));
        orgId = created.get("orgId").asString();
        firstTeamId = created.get("teamId").asString();
        insertOrgMembership(member, "MEMBER", "ACTIVE");
        insertOrgMembership(guest, "GUEST", "ACTIVE");
    }

    @Test
    @DisplayName("조직 정보: 구성원·손님·관리자는 자기 종류와 관리자 여부, 조직 밖은 404")
    void summaryShowsMyMembership() throws Exception {
        JsonNode byAdmin = json(perform(get(orgPath()), admin), 200);
        JsonNode byMember = json(perform(get(orgPath()), member), 200);
        JsonNode byGuest = json(perform(get(orgPath()), guest), 200);

        assertThat(byAdmin.get("name").asString()).isEqualTo("새벽 서재");
        assertThat(byAdmin.get("timeZone").asString()).isEqualTo("Asia/Seoul");
        assertThat(byAdmin.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(byAdmin.get("orgAdmin").asBoolean()).isTrue();
        assertThat(byMember.get("myKind").asString()).isEqualTo("MEMBER");
        assertThat(byMember.get("orgAdmin").asBoolean()).isFalse();
        assertThat(byGuest.get("myKind").asString()).isEqualTo("GUEST");
        assertThat(perform(get(orgPath()), outsider).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("API-OADM-03: 설정 응답은 정한 필드와 version만, 구성원 403, 손님 404")
    void settingsResponseHasOnlyKnownFields() throws Exception {
        JsonNode settings = json(perform(get(orgPath() + "/settings"), admin), 200);

        List<String> names = new ArrayList<>();
        settings.propertyNames().forEach(names::add);
        assertThat(names).containsExactlyInAnyOrder("name", "tagline", "timeZone", "visibility", "joinLinkEnabled",
                "joinRequestMode", "memberAdmissionReview", "guestApprovalRequired", "guestDefaultDays",
                "guestMaxDays", "guestOrgspaceAllowed", "invitationTtlDays", "state", "version");
        assertThat(settings.get("visibility").asString()).isEqualTo("PRIVATE");
        assertThat(settings.get("joinRequestMode").asString()).isEqualTo("OFF");
        assertThat(settings.get("memberAdmissionReview").asBoolean()).isTrue();
        assertThat(settings.get("guestMaxDays").asInt()).isEqualTo(90);
        assertThat(perform(get(orgPath() + "/settings"), member).getStatus()).isEqualTo(403);
        assertThat(perform(get(orgPath() + "/settings"), guest).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("설정 고치기: 보낸 값만 바뀌고 version 증가, 활동 기록에 바뀐 항목(이름 원문 제외)")
    void updatesSettings() throws Exception {
        Map<String, Object> body = body(version());
        body.put("name", "새벽 도서관");
        body.put("visibility", "PUBLIC");
        body.put("joinRequestMode", "APPROVAL");
        body.put("guestMaxDays", 60);
        body.put("timeZone", "UTC");

        JsonNode updated = json(update(admin, body), 200);

        assertThat(updated.get("name").asString()).isEqualTo("새벽 도서관");
        assertThat(updated.get("visibility").asString()).isEqualTo("PUBLIC");
        assertThat(updated.get("joinRequestMode").asString()).isEqualTo("APPROVAL");
        assertThat(updated.get("guestMaxDays").asInt()).isEqualTo(60);
        assertThat(updated.get("guestDefaultDays").asInt()).isEqualTo(30);
        assertThat(updated.get("version").asInt()).isEqualTo(1);
        Map<String, Object> log = jdbc.sql("""
                        SELECT before_value, after_value FROM org_activity_log
                        WHERE org_id = :org AND action = 'ORG_SETTINGS_CHANGED'
                        """)
                .param("org", orgId).query().singleRow();
        assertThat(log.get("before_value")).isEqualTo(
                "name;timeZone=Asia/Seoul;visibility=PRIVATE;joinRequestMode=OFF;guestMaxDays=90");
        assertThat(log.get("after_value")).isEqualTo(
                "name;timeZone=UTC;visibility=PUBLIC;joinRequestMode=APPROVAL;guestMaxDays=60");
    }

    @Test
    @DisplayName("같은 version으로 두 번 고치면 두 번째는 409 VERSION_CONFLICT, version 빠지면 400")
    void rejectsStaleVersion() throws Exception {
        int version = version();
        Map<String, Object> first = body(version);
        first.put("tagline", "아침마다 읽는 모임");
        Map<String, Object> second = body(version);
        second.put("tagline", "저녁마다 읽는 모임");

        assertThat(update(admin, first).getStatus()).isEqualTo(200);
        MockHttpServletResponse stale = update(admin, second);

        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
        assertThat(json(perform(get(orgPath() + "/settings"), admin), 200).get("tagline").asString())
                .isEqualTo("아침마다 읽는 모임");
        assertThat(fields(update(admin, Map.of("tagline", "버전 없음")))).containsExactly("version:REQUIRED");
    }

    @Test
    @DisplayName("T-AUTHZ-MASS: 설정 본문에 상태·관리자·소속·식별자를 끼워 넣으면 400, 아무것도 바뀌지 않음")
    void rejectsMassAssignment() throws Exception {
        int version = version();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("orgAdmin", true);
        extras.put("state", "SUSPENDED");
        extras.put("ownerless", true);
        extras.put("id", UUID.randomUUID());
        extras.put("orgId", UUID.randomUUID());
        extras.put("createdBy", outsider);
        extras.put("role", "ADMIN");
        for (Map.Entry<String, Object> extra : extras.entrySet()) {
            Map<String, Object> body = body(version);
            body.put("name", "바뀐 이름");
            body.put(extra.getKey(), extra.getValue());

            MockHttpServletResponse response = update(admin, body);

            assertThat(response.getStatus()).as(extra.getKey()).isEqualTo(400);
            assertThat(code(response)).isEqualTo("INVALID_REQUEST");
        }
        JsonNode settings = json(perform(get(orgPath() + "/settings"), admin), 200);
        assertThat(settings.get("name").asString()).isEqualTo("새벽 서재");
        assertThat(settings.get("version").asInt()).isEqualTo(version);
    }

    @Test
    @DisplayName("설정 값 검사: 비공개의 가입 요청, 손님 일수, 초대 기한, 시간대, 금지어")
    void validatesSettings() throws Exception {
        int version = version();

        assertThat(fields(update(admin, with(version, "joinRequestMode", "INSTANT"))))
                .containsExactly("joinRequestMode:NOT_ALLOWED_VALUE");
        assertThat(fields(update(admin, with(version, "guestMaxDays", 91))))
                .containsExactly("guestMaxDays:NOT_ALLOWED_VALUE");
        assertThat(fields(update(admin, with(version, "guestMaxDays", 20))))
                .containsExactly("guestDefaultDays:NOT_ALLOWED_VALUE");
        assertThat(fields(update(admin, with(version, "guestDefaultDays", 0))))
                .containsExactly("guestDefaultDays:NOT_ALLOWED_VALUE");
        assertThat(fields(update(admin, with(version, "invitationTtlDays", 31))))
                .containsExactly("invitationTtlDays:NOT_ALLOWED_VALUE");
        assertThat(fields(update(admin, with(version, "timeZone", "Mars/Olympus"))))
                .containsExactly("timeZone:NOT_ALLOWED_VALUE");
        assertThat(fields(update(admin, with(version, "name", "공식 서재")))).containsExactly("name:BANNED_WORD");
        assertThat(version()).isEqualTo(version);
    }

    @Test
    @DisplayName("비공개로 바꾸면 가입 요청은 OFF로 돌아감, 빈 한 줄 소개는 지움")
    void privateTurnsJoinRequestsOff() throws Exception {
        Map<String, Object> open = body(version());
        open.put("visibility", "PUBLIC");
        open.put("joinRequestMode", "INSTANT");
        open.put("tagline", "누구나 와요");
        json(update(admin, open), 200);

        Map<String, Object> close = body(version());
        close.put("visibility", "PRIVATE");
        close.put("tagline", " ");
        JsonNode closed = json(update(admin, close), 200);

        assertThat(closed.get("joinRequestMode").asString()).isEqualTo("OFF");
        assertThat(closed.get("tagline").isNull()).isTrue();
    }

    @Test
    @DisplayName("바뀐 값이 없으면 version 그대로, 활동 기록 없음")
    void noChangeKeepsVersion() throws Exception {
        Map<String, Object> body = body(version());
        body.put("name", "새벽 서재");
        body.put("visibility", "PRIVATE");

        assertThat(json(update(admin, body), 200).get("version").asInt()).isZero();
        assertThat(logCount("ORG_SETTINGS_CHANGED")).isZero();
    }

    @Test
    @DisplayName("SEC-SESS-10: 설정 고치기·보관·복원은 최근 인증이 지나면 401 REAUTH_REQUIRED")
    void requiresRecentAuth() throws Exception {
        Instant old = Instant.now().minus(Duration.ofHours(2));
        List<MockHttpServletRequestBuilder> requests = List.of(
                patch(orgPath() + "/settings").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(with(version(), "name", "늦은 이름"))),
                post(orgPath() + "/archive"),
                post(orgPath() + "/restore"));

        for (MockHttpServletRequestBuilder request : requests) {
            MockHttpServletResponse response = perform(request, admin, old);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(code(response)).isEqualTo("REAUTH_REQUIRED");
        }
        assertThat(orgState()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("TEN §14.1: 조직 보관은 대기 중인 조직·팀 가입 요청을 거절, 다시 보관하면 409 ALREADY_PROCESSED")
    void archivesOrg() throws Exception {
        UUID requester = newAccount("requester");
        UUID teamRequester = newAccount("team-requester");
        insertOrgMembership(requester, "MEMBER", "REQUESTED");
        insertTeamMembership(firstTeamId, member, "REQUESTED");
        insertOrgMembership(teamRequester, "MEMBER", "ACTIVE");
        insertTeamMembership(firstTeamId, teamRequester, "REQUESTED");

        assertThat(perform(post(orgPath() + "/archive"), admin).getStatus()).isEqualTo(204);

        assertThat(orgState()).isEqualTo("ARCHIVED");
        assertThat(jdbc.sql("SELECT state FROM org_membership WHERE org_id = :org AND account_id = :account")
                .param("org", orgId).param("account", requester.toString()).query(String.class).single())
                .isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM team_membership WHERE team_id = :team AND state = 'REJECTED'")
                .param("team", firstTeamId).query(Integer.class).single()).isEqualTo(2);
        assertThat(logCount("ORG_ARCHIVED")).isOne();
        assertThat(logCount("ORG_MEMBER_REJECTED")).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM team_activity_log WHERE action = 'TEAM_MEMBER_REJECTED'")
                .query(Integer.class).single()).isEqualTo(2);
        MockHttpServletResponse again = perform(post(orgPath() + "/archive"), admin);
        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("보관한 조직: 읽기는 되고 설정 고치기·팀 만들기는 409 READ_ONLY, 복원하면 다시 쓰기, 또 복원하면 409")
    void archivedOrgIsReadOnlyUntilRestored() throws Exception {
        perform(post(orgPath() + "/archive"), admin);

        assertThat(perform(get(orgPath()), member).getStatus()).isEqualTo(200);
        assertThat(perform(get(orgPath() + "/settings"), admin).getStatus()).isEqualTo(200);
        MockHttpServletResponse locked = update(admin, with(version(), "name", "보관 중 이름"));
        assertThat(locked.getStatus()).isEqualTo(409);
        assertThat(code(locked)).isEqualTo("READ_ONLY");

        assertThat(perform(post(orgPath() + "/restore"), admin).getStatus()).isEqualTo(204);

        assertThat(orgState()).isEqualTo("ACTIVE");
        assertThat(update(admin, with(version(), "name", "복원한 서재")).getStatus()).isEqualTo(200);
        assertThat(logCount("ORG_RESTORED")).isOne();
        MockHttpServletResponse again = perform(post(orgPath() + "/restore"), admin);
        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("관리자 없는 보관 조직은 복원 404, 구성원은 보관·복원 403")
    void ownerlessOrgCannotBeRestored() throws Exception {
        assertThat(perform(post(orgPath() + "/archive"), member).getStatus()).isEqualTo(403);
        jdbc.sql("UPDATE organization SET state = 'ARCHIVED', ownerless = :yes WHERE id = :org")
                .param("yes", true).param("org", orgId).update();

        MockHttpServletResponse response = perform(post(orgPath() + "/restore"), admin);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(orgState()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("개요: 팀별 인원, 조직 가입 요청 수, 최근 조직 활동 5개(최신부터), 구성원 403")
    void overviewSummarizesOrg() throws Exception {
        insertOrgMembership(newAccount("requester"), "MEMBER", "REQUESTED");
        insertTeamMembership(firstTeamId, member, "ACTIVE");
        for (String name : List.of("둘째 서재", "셋째 서재", "넷째 서재")) {
            update(admin, with(version(), "name", name));
        }

        JsonNode overview = json(perform(get(orgPath() + "/overview"), admin), 200);

        assertThat(overview.get("teamCount").asInt()).isOne();
        assertThat(overview.get("teams").get(0).get("memberCount").asLong()).isEqualTo(2);
        assertThat(overview.get("pendingJoinRequests").asLong()).isOne();
        assertThat(overview.get("pendingInvitations").asLong()).isZero();
        JsonNode recent = overview.get("recentActivity");
        assertThat(recent).hasSize(5);
        assertThat(recent.get(0).get("action").asString()).isEqualTo("ORG_SETTINGS_CHANGED");
        assertThat(recent.get(0).has("beforeValue")).isFalse();
        assertThat(perform(get(orgPath() + "/overview"), member).getStatus()).isEqualTo(403);
    }

    private String orgPath() {
        return "/api/orgs/" + orgId;
    }

    private int version() throws Exception {
        return json(perform(get(orgPath() + "/settings"), admin), 200).get("version").asInt();
    }

    private static Map<String, Object> body(int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        return body;
    }

    private static Map<String, Object> with(int version, String field, Object value) {
        Map<String, Object> body = body(version);
        body.put(field, value);
        return body;
    }

    private MockHttpServletResponse update(UUID actor, Map<String, Object> body) throws Exception {
        return perform(patch(orgPath() + "/settings").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)), actor);
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
        return perform(request, actor, Instant.now());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor, Instant authenticatedAt)
            throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, authenticatedAt)))
                .with(csrfToken())).andReturn().getResponse();
    }

    private void insertOrgMembership(UUID account, String kind, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version)
                        VALUES (:id, :org, :account, :kind, :admin, :state, 'INVITATION', :expires, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", account.toString())
                .param("kind", kind)
                .param("admin", false)
                .param("state", state)
                .param("expires", "GUEST".equals(kind) ? now.plusDays(30) : null)
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String teamId, UUID account, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, created_at,
                            updated_at, version)
                        VALUES (:id, :team, :account, 'TEAM_MEMBER', :state, 'REQUEST', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", teamId)
                .param("account", account.toString())
                .param("state", state)
                .param("now", now)
                .update();
    }

    private String orgState() {
        return jdbc.sql("SELECT state FROM organization WHERE id = :id").param("id", orgId)
                .query(String.class).single();
    }

    private int logCount(String action) {
        return jdbc.sql("SELECT COUNT(*) FROM org_activity_log WHERE org_id = :org AND action = :action")
                .param("org", orgId).param("action", action).query(Integer.class).single();
    }

    private List<String> fields(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(400);
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
