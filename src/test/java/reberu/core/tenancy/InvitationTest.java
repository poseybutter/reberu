package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
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
class InvitationTest {

    static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "invitation",
            "team_custom_field", "team_task_label", "team_membership", "team", "job_type", "org_membership",
            "organization", "api_idempotency_key", "account_consent", "account");

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
    private UUID outsider;
    private String orgId;
    private String teamId;
    private String otherTeamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        orgAdmin = newAccount("orgadmin@example.com");
        teamAdmin = newAccount("teamadmin@example.com");
        member = newAccount("haneul@example.com");
        outsider = newAccount("outsider@example.com");
        orgId = json(createOrg(orgAdmin, "새벽 서재"), 201).get("orgId").asString();
        insertOrgMembership(teamAdmin, "MEMBER");
        insertOrgMembership(member, "MEMBER");
        teamId = createTeam("개발 팀", teamAdmin);
        otherTeamId = createTeam("디자인 팀", teamAdmin);
    }

    @Test
    @DisplayName("SEC-TOKEN-09·TEN-INVT-01: 이메일은 앞뒤 공백 제거·소문자로 저장, 같은 요청의 중복은 하나로, 팀 없는 구성원 초대는 역할 없음")
    void normalizesEmails() throws Exception {
        JsonNode created = json(createForOrg(orgAdmin, invite(List.of("  Mina@Example.COM ", "mina@example.com",
                "Sol.Kim+work@Example.com"), "MEMBER", null, null, null)), 201);

        assertThat(values(created.get("invitations"), "email"))
                .containsExactly("mina@example.com", "sol.kim+work@example.com");
        assertThat(values(created.get("invitations"), "state")).containsExactly("PENDING", "PENDING");
        assertThat(jdbc.sql("SELECT email, role, team_id, guest_days FROM invitation ORDER BY email")
                .query((rs, rowNum) -> rs.getString("email") + " " + rs.getString("role") + " "
                        + rs.getString("team_id") + " " + rs.getString("guest_days"))
                .list())
                .containsExactly("mina@example.com null null null", "sol.kim+work@example.com null null null");
        Instant acceptBy = Instant.parse(created.get("invitations").get(0).get("acceptBy").asString());
        assertThat(Duration.between(Instant.now(), acceptBy)).isBetween(Duration.ofDays(7).minusMinutes(1),
                Duration.ofDays(7));
    }

    @Test
    @DisplayName("SEC-TOKEN-09·SEC-AUTHZ-10: 이메일 목록·종류·팀·역할·이용 일수가 규칙에 맞지 않으면 400, 초대를 만들지 않음")
    void rejectsInvalidInput() throws Exception {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add("user" + i + "@example.com");
        }
        String foreignTeam = foreignTeam();

        assertThat(fields(createForOrg(orgAdmin, invite(List.of(), "MEMBER", null, null, null))))
                .containsExactly("emails:REQUIRED");
        assertThat(fields(createForOrg(orgAdmin, invite(tooMany, "MEMBER", null, null, null))))
                .containsExactly("emails:TOO_LONG");
        for (String email : List.of("한글@example.com", "mina@exämple.com", "no-at.example.com", "a@b@example.com",
                "mina@example", "mi na@example.com", "", "x".repeat(65) + "@example.com")) {
            assertThat(fields(createForOrg(orgAdmin, invite(List.of(email), "MEMBER", null, null, null))))
                    .as(email).containsExactly("emails:INVALID_FORMAT");
        }
        assertThat(fields(createForOrg(orgAdmin, invite(List.of("a@example.com"), null, null, null, null))))
                .containsExactly("kind:REQUIRED");
        assertThat(fields(createForOrg(orgAdmin, invite(List.of("a@example.com"), "GUEST", null, null, 10))))
                .containsExactly("teamId:REQUIRED");
        assertThat(fields(createForOrg(orgAdmin, invite(List.of("a@example.com"), "MEMBER", null, "TEAM_MEMBER",
                null)))).containsExactly("role:NOT_ALLOWED_VALUE");
        assertThat(fields(createForOrg(orgAdmin, invite(List.of("a@example.com"), "MEMBER", teamId, null, null))))
                .containsExactly("role:REQUIRED");
        assertThat(fields(createForOrg(orgAdmin, invite(List.of("a@example.com"), "MEMBER", teamId, "TEAM_ADMIN",
                null)))).containsExactly("role:NOT_ALLOWED_VALUE");
        assertThat(fields(createForOrg(orgAdmin, invite(List.of("a@example.com"), "MEMBER", teamId, "VIEWER", 10))))
                .containsExactly("guestDays:NOT_ALLOWED_VALUE");
        assertThat(fields(createForOrg(orgAdmin, invite(List.of("a@example.com"), "GUEST", teamId, "VIEWER", 91))))
                .containsExactly("guestDays:NOT_ALLOWED_VALUE");
        assertThat(fields(createForOrg(orgAdmin, invite(List.of("a@example.com"), "GUEST", teamId, "VIEWER", 0))))
                .containsExactly("guestDays:NOT_ALLOWED_VALUE");
        assertThat(fields(createForOrg(orgAdmin, invite(List.of("a@example.com"), "MEMBER", foreignTeam,
                "VIEWER", null)))).containsExactly("teamId:NOT_ALLOWED_VALUE");
        assertThat(fields(createForTeam(teamAdmin, teamId, invite(List.of("a@example.com"), "GUEST", null,
                "TEAM_ADMIN", null)))).containsExactly("role:NOT_ALLOWED_VALUE");
        assertThat(count("invitation")).isZero();
    }

    @Test
    @DisplayName("TEN-INVT-02·SEC-TOKEN-12: 같은 조직·이메일·팀에 다시 초대하면 기존 대기 초대는 REVOKED, 다른 팀·팀 없음은 따로")
    void replacesOpenInvitation() throws Exception {
        String first = invitationId(createForOrg(orgAdmin, invite(List.of("mina@example.com"), "MEMBER", teamId,
                "VIEWER", null)));
        String orgOnly = invitationId(createForOrg(orgAdmin, invite(List.of("mina@example.com"), "MEMBER", null,
                null, null)));
        String other = invitationId(createForTeam(teamAdmin, otherTeamId, invite(List.of("mina@example.com"),
                "MEMBER", null, "VIEWER", null)));

        String second = invitationId(createForTeam(teamAdmin, teamId, invite(List.of("MINA@example.com"), "MEMBER",
                null, "TEAM_MEMBER", null)));

        assertThat(state(first)).isEqualTo("REVOKED");
        assertThat(state(orgOnly)).isEqualTo("PENDING");
        assertThat(state(other)).isEqualTo("PENDING_APPROVAL");
        assertThat(state(second)).isEqualTo("PENDING_APPROVAL");
        assertThat(teamLog(teamId, "INVITATION_REVOKED")).containsExactly(teamAdmin + " " + first + " PENDING REVOKED");
    }

    @Test
    @DisplayName("TEN-ADM-01·TEN-TST-15: 팀 관리자의 구성원 초대는 ACTIVE 구성원이 아닌 사람만 승인 대기, 조직 관리자 초대와 설정 끔은 바로 PENDING")
    void teamAdminMemberInvitationNeedsReview() throws Exception {
        JsonNode byTeamAdmin = json(createForTeam(teamAdmin, teamId, invite(List.of("haneul@example.com",
                "iseul@example.com"), "MEMBER", null, "TEAM_MEMBER", null)), 201);
        JsonNode byOrgAdmin = json(createForTeam(orgAdmin, otherTeamId, invite(List.of("iseul@example.com"),
                "MEMBER", null, "TEAM_MEMBER", null)), 201);
        jdbc.sql("UPDATE organization SET member_admission_review = :off").param("off", false).update();
        JsonNode reviewOff = json(createForTeam(teamAdmin, teamId, invite(List.of("dawn@example.com"), "MEMBER",
                null, "VIEWER", null)), 201);

        assertThat(values(byTeamAdmin.get("invitations"), "state")).containsExactly("PENDING", "PENDING_APPROVAL");
        assertThat(values(byOrgAdmin.get("invitations"), "state")).containsExactly("PENDING");
        assertThat(values(reviewOff.get("invitations"), "state")).containsExactly("PENDING");
        assertThat(teamLog(teamId, "INVITATION_CREATED")).hasSize(3)
                .allSatisfy(entry -> assertThat(entry).doesNotContain("@"));
    }

    @Test
    @DisplayName("TEN-GUEST-01·§7.6: 손님 초대는 팀 필수, 이용 일수 기본 30일, 팀 관리자가 만들면 손님 승인 설정에 따라 승인 대기")
    void guestInvitations() throws Exception {
        JsonNode byTeamAdmin = json(createForTeam(teamAdmin, teamId, invite(List.of("guest@example.com"), "GUEST",
                null, "VIEWER", null)), 201);
        JsonNode byOrgAdmin = json(createForOrg(orgAdmin, invite(List.of("guest2@example.com"), "GUEST", teamId,
                "TEAM_MEMBER", 90)), 201);
        jdbc.sql("UPDATE organization SET guest_approval_required = :off").param("off", false).update();
        JsonNode approvalOff = json(createForTeam(teamAdmin, teamId, invite(List.of("haneul@example.com"), "GUEST",
                null, "VIEWER", 5)), 201);

        assertThat(values(byTeamAdmin.get("invitations"), "state")).containsExactly("PENDING_APPROVAL");
        assertThat(values(byOrgAdmin.get("invitations"), "state")).containsExactly("PENDING");
        assertThat(values(approvalOff.get("invitations"), "state")).containsExactly("PENDING");
        assertThat(jdbc.sql("SELECT guest_days FROM invitation ORDER BY created_at, id").query(Integer.class).list())
                .containsExactly(30, 90, 5);
    }

    @Test
    @DisplayName("TEN-ADM-01·§5.6: 조직 관리자 승인은 PENDING·수락 기한 새로, 거절은 REVOKED, 다시 처리하면 409, overview에 승인 대기 수")
    void reviewsPendingApproval() throws Exception {
        List<String> ids = invitationIds(createForTeam(teamAdmin, teamId, invite(List.of("a@example.com",
                "b@example.com", "c@example.com"), "MEMBER", null, "VIEWER", null)));
        jdbc.sql("UPDATE invitation SET accept_by = :old").param("old", LocalDateTime.now(ZoneOffset.UTC).minusDays(1))
                .update();
        assertThat(overviewPending()).isEqualTo(3);

        assertThat(review(orgAdmin, ids.get(0), "approve").getStatus()).isEqualTo(204);
        assertThat(review(orgAdmin, ids.get(1), "reject").getStatus()).isEqualTo(204);

        assertThat(state(ids.get(0))).isEqualTo("PENDING");
        assertThat(state(ids.get(1))).isEqualTo("REVOKED");
        assertThat(jdbc.sql("SELECT accept_by FROM invitation WHERE id = :id").param("id", ids.get(0))
                .query(LocalDateTime.class).single()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusDays(6));
        assertThat(jdbc.sql("SELECT approved_by FROM invitation WHERE id = :id").param("id", ids.get(0))
                .query(String.class).single()).isEqualTo(orgAdmin.toString());
        for (String action : List.of("approve", "reject")) {
            assertThat(code(review(orgAdmin, ids.get(0), action), 409)).isEqualTo("ALREADY_PROCESSED");
            assertThat(code(review(orgAdmin, ids.get(1), action), 409)).isEqualTo("ALREADY_PROCESSED");
        }
        assertThat(overviewPending()).isEqualTo(1);
        assertThat(teamLog(teamId, "INVITATION_APPROVED"))
                .containsExactly(orgAdmin + " " + ids.get(0) + " PENDING_APPROVAL PENDING");
        assertThat(teamLog(teamId, "INVITATION_REJECTED"))
                .containsExactly(orgAdmin + " " + ids.get(1) + " PENDING_APPROVAL REVOKED");
    }

    @Test
    @DisplayName("SEC-SESS-10·TEN-PERM: 승인·거절은 최근 인증 필요, 팀 관리자는 조직 초대 API 403, 남의 조직 초대는 404, 보관한 팀의 초대 승인은 409 READ_ONLY")
    void reviewIsOrgAdminOnly() throws Exception {
        List<String> ids = invitationIds(createForTeam(teamAdmin, teamId, invite(List.of("a@example.com",
                "b@example.com"), "MEMBER", null, "VIEWER", null)));
        String foreignOrg = json(createOrg(outsider, "다른 조직"), 201).get("orgId").asString();

        assertThat(code(perform(post("/api/orgs/" + orgId + "/invitations/" + ids.get(0) + "/approve"), orgAdmin,
                Instant.now().minus(Duration.ofHours(2))), 401)).isEqualTo("REAUTH_REQUIRED");
        assertThat(code(review(teamAdmin, ids.get(0), "approve"), 403)).isEqualTo("FORBIDDEN_ACTION");
        assertThat(code(perform(get("/api/orgs/" + orgId + "/invitations"), teamAdmin), 403))
                .isEqualTo("FORBIDDEN_ACTION");
        assertThat(code(perform(post("/api/orgs/" + foreignOrg + "/invitations/" + ids.get(0) + "/approve"),
                outsider), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(review(orgAdmin, UUID.randomUUID().toString(), "approve"), 404)).isEqualTo("NOT_FOUND");
        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :team").param("team", teamId).update();
        assertThat(code(review(orgAdmin, ids.get(1), "approve"), 409)).isEqualTo("READ_ONLY");
        assertThat(review(orgAdmin, ids.get(1), "reject").getStatus()).isEqualTo(204);
        assertThat(state(ids.get(0))).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("§5.6: 취소는 대기 초대만 REVOKED, 다시 취소·기한 지난 초대 취소는 409, 팀 경로는 그 팀 초대만")
    void revokesInvitations() throws Exception {
        List<String> ids = invitationIds(createForOrg(orgAdmin, invite(List.of("a@example.com", "b@example.com",
                "c@example.com"), "MEMBER", teamId, "VIEWER", null)));
        String otherTeam = invitationId(createForOrg(orgAdmin, invite(List.of("a@example.com"), "MEMBER",
                otherTeamId, "VIEWER", null)));
        String orgOnly = invitationId(createForOrg(orgAdmin, invite(List.of("a@example.com"), "MEMBER", null, null,
                null)));
        jdbc.sql("UPDATE invitation SET accept_by = :old WHERE id = :id")
                .param("old", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).param("id", ids.get(2)).update();

        assertThat(revokeForTeam(teamAdmin, teamId, ids.get(0)).getStatus()).isEqualTo(204);
        assertThat(code(revokeForTeam(teamAdmin, teamId, ids.get(0)), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(code(revokeForTeam(teamAdmin, teamId, ids.get(2)), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(code(revokeForTeam(teamAdmin, teamId, otherTeam), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(revokeForTeam(teamAdmin, teamId, orgOnly), 404)).isEqualTo("NOT_FOUND");
        assertThat(perform(post("/api/orgs/" + orgId + "/invitations/" + ids.get(1) + "/revoke"), orgAdmin)
                .getStatus()).isEqualTo(204);
        assertThat(perform(post("/api/orgs/" + orgId + "/invitations/" + orgOnly + "/revoke"), orgAdmin)
                .getStatus()).isEqualTo(204);

        assertThat(state(ids.get(0))).isEqualTo("REVOKED");
        assertThat(state(ids.get(1))).isEqualTo("REVOKED");
        assertThat(state(ids.get(2))).isEqualTo("PENDING");
        assertThat(state(otherTeam)).isEqualTo("PENDING");
        assertThat(orgLog("INVITATION_REVOKED")).containsExactly(orgAdmin + " " + orgOnly + " PENDING REVOKED");
        assertThat(code(revokeForTeam(member, teamId, otherTeam), 404)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("§8·§9 목록: 최신부터, 기한 지난 PENDING은 EXPIRED로 보이고 걸러짐, state·kind·teamId 조건, 커서, 팀 목록은 그 팀만")
    void listsInvitations() throws Exception {
        String orgOnly = invitationId(createForOrg(orgAdmin, invite(List.of("a@example.com"), "MEMBER", null, null,
                null)));
        String guest = invitationId(createForTeam(teamAdmin, teamId, invite(List.of("g@example.com"), "GUEST", null,
                "VIEWER", null)));
        String expired = invitationId(createForOrg(orgAdmin, invite(List.of("e@example.com"), "MEMBER", teamId,
                "VIEWER", null)));
        String other = invitationId(createForOrg(orgAdmin, invite(List.of("o@example.com"), "MEMBER", otherTeamId,
                "VIEWER", null)));
        jdbc.sql("UPDATE invitation SET accept_by = :old WHERE id = :id")
                .param("old", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).param("id", expired).update();

        JsonNode all = json(perform(get("/api/orgs/" + orgId + "/invitations"), orgAdmin), 200);
        assertThat(values(all.get("items"), "invitationId")).containsExactly(other, expired, guest, orgOnly);
        assertThat(values(all.get("items"), "state")).containsExactly("PENDING", "EXPIRED", "PENDING_APPROVAL",
                "PENDING");
        JsonNode first = all.get("items").get(2);
        assertThat(first.get("email").asString()).isEqualTo("g@example.com");
        assertThat(first.get("teamName").asString()).isEqualTo("개발 팀");
        assertThat(first.get("guestDays").asInt()).isEqualTo(30);
        assertThat(first.get("createdByName").asString()).isEqualTo("teamadmin");
        assertThat(ids("/api/orgs/" + orgId + "/invitations?state=EXPIRED")).containsExactly(expired);
        assertThat(ids("/api/orgs/" + orgId + "/invitations?state=PENDING")).containsExactly(other, orgOnly);
        assertThat(ids("/api/orgs/" + orgId + "/invitations?kind=GUEST")).containsExactly(guest);
        assertThat(ids("/api/orgs/" + orgId + "/invitations?teamId=" + teamId)).containsExactly(expired, guest);
        assertThat(ids("/api/teams/" + teamId + "/invitations", teamAdmin)).containsExactly(expired, guest);
        JsonNode page = json(perform(get("/api/orgs/" + orgId + "/invitations?limit=3"), orgAdmin), 200);
        assertThat(ids("/api/orgs/" + orgId + "/invitations?limit=3&cursor=" + page.get("nextCursor").asString()))
                .containsExactly(orgOnly);
        assertThat(code(perform(get("/api/teams/" + teamId + "/invitations"), member), 404)).isEqualTo("NOT_FOUND");
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, created_at,
                            updated_at, version)
                        VALUES (:id, :team, :account, 'TEAM_MEMBER', 'ACTIVE', 'DIRECT', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString()).param("team", teamId)
                .param("account", member.toString()).param("now", LocalDateTime.now(ZoneOffset.UTC)).update();
        assertThat(code(perform(get("/api/teams/" + teamId + "/invitations"), member), 403))
                .isEqualTo("FORBIDDEN_ACTION");
        assertThat(code(createForTeam(member, teamId, invite(List.of("x@example.com"), "MEMBER", null, "VIEWER",
                null)), 403)).isEqualTo("FORBIDDEN_ACTION");
    }

    private List<String> ids(String path) throws Exception {
        return ids(path, orgAdmin);
    }

    private List<String> ids(String path, UUID actor) throws Exception {
        return values(json(perform(get(path), actor), 200).get("items"), "invitationId");
    }

    private long overviewPending() throws Exception {
        return json(perform(get("/api/orgs/" + orgId + "/overview"), orgAdmin), 200).get("pendingInvitations")
                .asLong();
    }

    private String foreignTeam() throws Exception {
        String foreignOrg = json(createOrg(outsider, "남의 조직"), 201).get("orgId").asString();
        return jdbc.sql("SELECT id FROM team WHERE org_id = :org").param("org", foreignOrg).query(String.class)
                .single();
    }

    static Map<String, Object> invite(List<String> emails, String kind, String team, String role, Integer days) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("emails", emails);
        body.put("kind", kind);
        if (team != null) {
            body.put("teamId", team);
        }
        body.put("role", role);
        body.put("guestDays", days);
        return body;
    }

    private MockHttpServletResponse createForOrg(UUID actor, Map<String, Object> body) throws Exception {
        return perform(post("/api/orgs/" + orgId + "/invitations")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), actor);
    }

    private MockHttpServletResponse createForTeam(UUID actor, String team, Map<String, Object> body)
            throws Exception {
        return perform(post("/api/teams/" + team + "/invitations")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), actor);
    }

    private MockHttpServletResponse review(UUID actor, String invitation, String action) throws Exception {
        return perform(post("/api/orgs/" + orgId + "/invitations/" + invitation + "/" + action), actor);
    }

    private MockHttpServletResponse revokeForTeam(UUID actor, String team, String invitation) throws Exception {
        return perform(post("/api/teams/" + team + "/invitations/" + invitation + "/revoke"), actor);
    }

    private String invitationId(MockHttpServletResponse response) throws Exception {
        List<String> ids = invitationIds(response);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private List<String> invitationIds(MockHttpServletResponse response) throws Exception {
        return values(json(response, 201).get("invitations"), "invitationId");
    }

    private String state(String invitation) {
        return jdbc.sql("SELECT state FROM invitation WHERE id = :id").param("id", invitation).query(String.class)
                .single();
    }

    private List<String> teamLog(String team, String action) {
        return log("team_activity_log", "team_id", team, action);
    }

    private List<String> orgLog(String action) {
        return log("org_activity_log", "org_id", orgId, action);
    }

    private List<String> log(String table, String column, String scope, String action) {
        return jdbc.sql("SELECT actor_id, target_id, before_value, after_value FROM " + table + " WHERE " + column
                        + " = :scope AND action = :action ORDER BY occurred_at, id")
                .param("scope", scope).param("action", action)
                .query((rs, rowNum) -> rs.getString("actor_id") + " " + rs.getString("target_id")
                        + (rs.getString("before_value") == null ? "" : " " + rs.getString("before_value")) + " "
                        + rs.getString("after_value"))
                .list();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private String createTeam(String name, UUID firstAdmin) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", orgId).query(String.class).single());
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

    private void insertOrgMembership(UUID account, String kind) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            created_at, updated_at, version)
                        VALUES (:id, :org, :account, :kind, :admin, 'ACTIVE', 'INVITATION', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", account.toString())
                .param("kind", kind)
                .param("admin", false)
                .param("now", now)
                .update();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return perform(request, actor, Instant.now());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor, Instant authenticatedAt)
            throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, authenticatedAt)))
                .with(csrfToken())).andReturn().getResponse();
    }

    private static List<String> values(JsonNode items, String field) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.get(field).isNull() ? null : item.get(field).asString()));
        return Collections.unmodifiableList(values);
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

    private UUID newAccount(String email) {
        String name = email.substring(0, email.indexOf('@')).toLowerCase();
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(), email, name,
                null, null), "v1", "v1", Instant.now());
    }

}
