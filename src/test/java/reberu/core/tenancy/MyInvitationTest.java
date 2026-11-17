package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;
import static reberu.core.tenancy.InvitationTest.invite;

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
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
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
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.service.InvitationService;
import reberu.core.tenancy.service.InvitationService.Acceptance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MyInvitationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    InvitationService invitations;

    @Autowired
    TeamMembershipRepository teamMemberships;

    private UUID orgAdmin;
    private UUID teamAdmin;
    private String orgId;
    private String teamId;
    private String otherTeamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : InvitationTest.TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        orgAdmin = newAccount("orgadmin@example.com");
        teamAdmin = newAccount("teamadmin@example.com");
        orgId = json(createOrg(orgAdmin, "새벽 서재"), 201).get("orgId").asString();
        insertOrgMembership(teamAdmin, "MEMBER", "ACTIVE", null);
        teamId = createTeam("개발 팀", teamAdmin);
        otherTeamId = createTeam("디자인 팀", teamAdmin);
    }

    @Test
    @DisplayName("T-INV-EMAIL: 확인된 이메일과 정규화 후 정확히 같은 계정만 보고 수락, 점·+ 꼬리만 다른 계정과 남의 초대는 404")
    void acceptsOnlyExactEmail() throws Exception {
        String invitation = invitation("mina@example.com", "MEMBER", teamId, "TEAM_MEMBER", null);
        UUID plus = newAccount("mina+work@example.com");
        UUID dotted = newAccount("m.ina@example.com");
        UUID upper = newAccount("MINA@Example.com");

        for (UUID other : List.of(plus, dotted, orgAdmin)) {
            assertThat(items(other)).isEmpty();
            assertThat(code(accept(other, invitation), 404)).isEqualTo("NOT_FOUND");
            assertThat(code(decline(other, invitation), 404)).isEqualTo("NOT_FOUND");
        }
        assertThat(code(accept(upper, UUID.randomUUID().toString()), 404)).isEqualTo("NOT_FOUND");
        assertThat(state(invitation)).isEqualTo("PENDING");
        assertThat(values(items(upper), "invitationId")).containsExactly(invitation);

        assertThat(accept(upper, invitation).getStatus()).isEqualTo(200);
        assertThat(state(invitation)).isEqualTo("ACCEPTED");
        assertThat(jdbc.sql("SELECT accepted_by FROM invitation WHERE id = :id").param("id", invitation)
                .query(String.class).single()).isEqualTo(upper.toString());
    }

    @Test
    @DisplayName("T-INV-EMAIL·SEC-TOKEN-10: 수락 순간 다시 확인, 취소·처리됨은 409, 기한 지남·조직이나 팀 보관은 EXPIRED로 바꾸고 409, 승인 대기는 404")
    void rechecksAtAcceptance() throws Exception {
        UUID mina = newAccount("mina@example.com");
        String revoked = invitation("mina@example.com", "MEMBER", teamId, "TEAM_MEMBER", null);
        perform(post("/api/orgs/" + orgId + "/invitations/" + revoked + "/revoke"), orgAdmin);
        assertThat(code(accept(mina, revoked), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(state(revoked)).isEqualTo("REVOKED");

        String overdue = invitation("mina@example.com", "MEMBER", otherTeamId, "TEAM_MEMBER", null);
        jdbc.sql("UPDATE invitation SET accept_by = :past WHERE id = :id")
                .param("past", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).param("id", overdue).update();
        assertThat(code(accept(mina, overdue), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(state(overdue)).isEqualTo("EXPIRED");
        assertThat(teamLog(otherTeamId, "INVITATION_EXPIRED")).containsExactly(mina + " " + overdue + " PENDING EXPIRED");

        String archivedTeam = invitation("mina@example.com", "MEMBER", teamId, "TEAM_MEMBER", null);
        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :id").param("id", teamId).update();
        assertThat(code(accept(mina, archivedTeam), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(state(archivedTeam)).isEqualTo("EXPIRED");
        jdbc.sql("UPDATE team SET state = 'ACTIVE' WHERE id = :id").param("id", teamId).update();

        String archivedOrg = invitation("mina@example.com", "MEMBER", null, null, null);
        jdbc.sql("UPDATE organization SET state = 'ARCHIVED' WHERE id = :id").param("id", orgId).update();
        assertThat(code(accept(mina, archivedOrg), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(state(archivedOrg)).isEqualTo("EXPIRED");
        assertThat(orgLog("INVITATION_EXPIRED")).containsExactly(mina + " " + archivedOrg + " PENDING EXPIRED");
        jdbc.sql("UPDATE organization SET state = 'ACTIVE' WHERE id = :id").param("id", orgId).update();

        jdbc.sql("UPDATE organization SET member_admission_review = :on WHERE id = :id").param("on", true)
                .param("id", orgId).update();
        String review = invitationId(createForTeam(teamAdmin, teamId,
                invite(List.of("mina@example.com"), "MEMBER", null, "TEAM_MEMBER", null)));
        assertThat(state(review)).isEqualTo("PENDING_APPROVAL");
        assertThat(code(accept(mina, review), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(decline(mina, review), 404)).isEqualTo("NOT_FOUND");

        String accepted = invitation("mina@example.com", "MEMBER", otherTeamId, "TEAM_MEMBER", null);
        assertThat(accept(mina, accepted).getStatus()).isEqualTo(200);
        assertThat(code(accept(mina, accepted), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(code(decline(mina, accepted), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(count("org_membership WHERE account_id = '" + mina + "'")).isOne();
    }

    @Test
    @DisplayName("§7.3: 새 계정의 팀 초대 수락, 조직·팀 멤버십 ACTIVE와 기록, 다음 화면은 그 팀 첫 화면")
    void newAccountAcceptsTeamInvitation() throws Exception {
        UUID mina = newAccount("mina@example.com");
        String invitation = invitation("mina@example.com", "MEMBER", teamId, "VIEWER", null);

        JsonNode next = json(accept(mina, invitation), 200).get("next");

        assertThat(next.get("screen").asString()).isEqualTo("TEAM_HOME");
        assertThat(next.get("teamId").asString()).isEqualTo(teamId);
        assertThat(orgMember(mina)).isEqualTo("ACTIVE MEMBER INVITATION false null");
        assertThat(teamMember(teamId, mina)).isEqualTo("ACTIVE VIEWER INVITATION null");
        assertThat(orgLog("ORG_MEMBER_JOINED")).contains(mina + " " + mina + " MEMBER");
        assertThat(teamLog(teamId, "TEAM_MEMBER_JOINED")).contains(mina + " " + mina + " VIEWER");
        assertThat(teamLog(teamId, "INVITATION_ACCEPTED")).containsExactly(mina + " " + invitation
                + " PENDING ACCEPTED");
        assertThat(json(perform(get("/api/teams/" + teamId), mina), 200).get("myRole").asString())
                .isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("§7.3: 팀 없는 구성원 초대는 조직 멤버십만, 다음 화면은 첫 화면 판정과 같음")
    void orgOnlyInvitationCreatesOrgMembership() throws Exception {
        UUID mina = newAccount("mina@example.com");
        String invitation = invitation("mina@example.com", "MEMBER", null, null, null);

        JsonNode next = json(accept(mina, invitation), 200).get("next");

        assertThat(next.get("screen").asString()).isEqualTo("TEAM_PICKER");
        assertThat(json(perform(get("/api/me/home"), mina), 200).get("next")).isEqualTo(next);
        assertThat(orgMember(mina)).isEqualTo("ACTIVE MEMBER INVITATION false null");
        assertThat(count("team_membership WHERE account_id = '" + mina + "'")).isZero();
        assertThat(orgLog("INVITATION_ACCEPTED")).containsExactly(mina + " " + invitation + " PENDING ACCEPTED");
    }

    @Test
    @DisplayName("TEN-AUTH-11: 이미 ACTIVE 구성원이면 팀 멤버십만, 이미 ACTIVE 팀 멤버면 초대만 ACCEPTED이고 역할은 그대로")
    void activeMemberKeepsMemberships() throws Exception {
        UUID mina = newAccount("mina@example.com");
        insertOrgMembership(mina, "MEMBER", "ACTIVE", null);
        insertTeamMembership(otherTeamId, mina, "VIEWER", "ACTIVE", null);

        assertThat(accept(mina, invitation("mina@example.com", "MEMBER", teamId, "TEAM_MEMBER", null)).getStatus())
                .isEqualTo(200);
        String same = invitation("mina@example.com", "MEMBER", otherTeamId, "TEAM_MEMBER", null);
        assertThat(accept(mina, same).getStatus()).isEqualTo(200);
        assertThat(accept(mina, invitation("mina@example.com", "MEMBER", null, null, null)).getStatus())
                .isEqualTo(200);

        assertThat(teamMember(teamId, mina)).isEqualTo("ACTIVE TEAM_MEMBER INVITATION null");
        assertThat(teamMember(otherTeamId, mina)).isEqualTo("ACTIVE VIEWER INVITATION null");
        assertThat(state(same)).isEqualTo("ACCEPTED");
        assertThat(orgLog("ORG_MEMBER_JOINED")).noneMatch(line -> line.startsWith(mina.toString()));
        assertThat(teamLog(otherTeamId, "TEAM_MEMBER_JOINED")).noneMatch(line -> line.startsWith(mina.toString()));
    }

    @Test
    @DisplayName("TEN-AUTH-12·TEN-INVT-16: 조직 멤버십 정지나 팀 멤버십만 정지면 403, 초대는 PENDING으로 남음")
    void suspendedMembershipCannotAccept() throws Exception {
        UUID mina = newAccount("mina@example.com");
        insertOrgMembership(mina, "MEMBER", "SUSPENDED", null);
        String orgSuspended = invitation("mina@example.com", "MEMBER", teamId, "TEAM_MEMBER", null);
        assertThat(code(accept(mina, orgSuspended), 403)).isEqualTo("FORBIDDEN_ACTION");
        assertThat(state(orgSuspended)).isEqualTo("PENDING");
        assertThat(count("team_membership WHERE account_id = '" + mina + "'")).isZero();

        UUID sol = newAccount("sol@example.com");
        insertOrgMembership(sol, "MEMBER", "ACTIVE", null);
        insertTeamMembership(teamId, sol, "TEAM_MEMBER", "SUSPENDED", null);
        String teamSuspended = invitation("sol@example.com", "MEMBER", teamId, "VIEWER", null);
        assertThat(code(accept(sol, teamSuspended), 403)).isEqualTo("FORBIDDEN_ACTION");
        assertThat(state(teamSuspended)).isEqualTo("PENDING");
        assertThat(teamMember(teamId, sol)).isEqualTo("SUSPENDED TEAM_MEMBER INVITATION null");
    }

    @Test
    @DisplayName("TEN-AUTH-14: 만료 전 손님에게 온 구성원 초대는 403, 초대는 PENDING")
    void guestCannotAcceptMemberInvitation() throws Exception {
        UUID mina = newAccount("mina@example.com");
        insertOrgMembership(mina, "GUEST", "ACTIVE", LocalDateTime.now(ZoneOffset.UTC).plusDays(5));
        String invitation = invitation("mina@example.com", "MEMBER", teamId, "TEAM_MEMBER", null);

        assertThat(code(accept(mina, invitation), 403)).isEqualTo("FORBIDDEN_ACTION");
        assertThat(state(invitation)).isEqualTo("PENDING");
        assertThat(orgMember(mina)).startsWith("ACTIVE GUEST");
    }

    @Test
    @DisplayName("TEN-AUTH-13: 구성원에게 온 손님 초대는 멤버 종류 그대로, 팀 멤버십 만료 시각 없음")
    void memberAcceptsGuestInvitationWithoutExpiry() throws Exception {
        UUID mina = newAccount("mina@example.com");
        insertOrgMembership(mina, "MEMBER", "ACTIVE", null);

        assertThat(accept(mina, invitation("mina@example.com", "GUEST", teamId, "TEAM_MEMBER", 10)).getStatus())
                .isEqualTo(200);

        assertThat(orgMember(mina)).isEqualTo("ACTIVE MEMBER INVITATION false null");
        assertThat(teamMember(teamId, mina)).isEqualTo("ACTIVE TEAM_MEMBER INVITATION null");
    }

    @Test
    @DisplayName("§7.6·TEN-INVT-17: 손님 팀 만료 시각은 수락 시각 + 이용 일수, 조직 만료 시각은 가장 늦은 팀 만료 시각, ACTIVE 팀에 다시 온 손님 초대는 만료 시각 그대로")
    void guestExpiryFollowsTeams() throws Exception {
        UUID mina = newAccount("mina@example.com");
        JsonNode next = json(accept(mina, invitation("mina@example.com", "GUEST", teamId, "VIEWER", 10)), 200)
                .get("next");
        assertThat(next.get("teamId").asString()).isEqualTo(teamId);
        LocalDateTime first = teamExpiresAt(teamId, mina);
        assertThat(Duration.between(LocalDateTime.now(ZoneOffset.UTC), first))
                .isBetween(Duration.ofDays(10).minusMinutes(1), Duration.ofDays(10));
        assertThat(orgExpiresAt(mina)).isEqualTo(first);
        assertThat(orgMember(mina)).startsWith("ACTIVE GUEST INVITATION false");
        assertThat(orgLog("ORG_MEMBER_JOINED")).contains(mina + " " + mina + " GUEST");

        assertThat(accept(mina, invitation("mina@example.com", "GUEST", otherTeamId, "TEAM_MEMBER", 20))
                .getStatus()).isEqualTo(200);
        LocalDateTime second = teamExpiresAt(otherTeamId, mina);
        assertThat(second).isAfter(first.plusDays(9));
        assertThat(orgExpiresAt(mina)).isEqualTo(second);

        assertThat(accept(mina, invitation("mina@example.com", "GUEST", teamId, "TEAM_MEMBER", 30)).getStatus())
                .isEqualTo(200);
        assertThat(teamExpiresAt(teamId, mina)).isEqualTo(first);
        assertThat(teamMember(teamId, mina)).startsWith("ACTIVE VIEWER");
        assertThat(orgExpiresAt(mina)).isEqualTo(second);
    }

    @Test
    @DisplayName("TEN-INVT-16: 끝난 멤버십(LEFT·REJECTED·REQUESTED·만료된 손님)은 초대 수락으로 ACTIVE, 만료된 손님의 구성원 초대는 구성원")
    void endedMembershipsRejoin() throws Exception {
        UUID left = newAccount("left@example.com");
        insertOrgMembership(left, "MEMBER", "LEFT", null);
        insertTeamMembership(teamId, left, "TEAM_MEMBER", "LEFT", null);
        assertThat(accept(left, invitation("left@example.com", "MEMBER", teamId, "VIEWER", null)).getStatus())
                .isEqualTo(200);
        assertThat(orgMember(left)).isEqualTo("ACTIVE MEMBER INVITATION false null");
        assertThat(teamMember(teamId, left)).isEqualTo("ACTIVE VIEWER INVITATION null");
        assertThat(orgLog("ORG_MEMBER_JOINED")).contains(left + " " + left + " LEFT MEMBER");
        assertThat(teamLog(teamId, "TEAM_MEMBER_JOINED")).contains(left + " " + left + " LEFT VIEWER");

        UUID rejected = newAccount("rejected@example.com");
        insertOrgMembership(rejected, "MEMBER", "REJECTED", null);
        UUID requested = newAccount("requested@example.com");
        insertOrgMembership(requested, "MEMBER", "REQUESTED", null);
        insertTeamMembership(teamId, requested, "TEAM_MEMBER", "REQUESTED", null);
        assertThat(accept(rejected, invitation("rejected@example.com", "MEMBER", null, null, null)).getStatus())
                .isEqualTo(200);
        assertThat(accept(requested, invitation("requested@example.com", "MEMBER", teamId, "VIEWER", null))
                .getStatus()).isEqualTo(200);
        assertThat(orgMember(rejected)).startsWith("ACTIVE MEMBER");
        assertThat(orgMember(requested)).startsWith("ACTIVE MEMBER");
        assertThat(teamMember(teamId, requested)).isEqualTo("ACTIVE VIEWER INVITATION null");

        UUID expired = newAccount("expired@example.com");
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        insertOrgMembership(expired, "GUEST", "ACTIVE", past);
        insertTeamMembership(teamId, expired, "TEAM_MEMBER", "ACTIVE", past);
        assertThat(accept(expired, invitation("expired@example.com", "MEMBER", teamId, "VIEWER", null)).getStatus())
                .isEqualTo(200);
        assertThat(orgMember(expired)).isEqualTo("ACTIVE MEMBER INVITATION false null");
        assertThat(teamMember(teamId, expired)).isEqualTo("ACTIVE VIEWER INVITATION null");
        assertThat(orgLog("ORG_MEMBER_JOINED")).contains(expired + " " + expired + " EXPIRED MEMBER");

        UUID expiredGuest = newAccount("again@example.com");
        insertOrgMembership(expiredGuest, "GUEST", "ACTIVE", past);
        assertThat(accept(expiredGuest, invitation("again@example.com", "GUEST", teamId, "VIEWER", 7)).getStatus())
                .isEqualTo(200);
        assertThat(orgExpiresAt(expiredGuest)).isEqualTo(teamExpiresAt(teamId, expiredGuest))
                .isAfter(LocalDateTime.now(ZoneOffset.UTC).plusDays(6));
    }

    @Test
    @DisplayName("TEN-AUTH-10: 팀 멤버십 저장이 실패하면 초대·조직 멤버십·기록 모두 되돌림")
    void rollsBackWhenAnyStepFails() throws Exception {
        UUID mina = newAccount("mina@example.com");
        String invitation = invitation("mina@example.com", "MEMBER", teamId, "TEAM_MEMBER", null);
        long logs = count("org_activity_log") + count("team_activity_log");
        MethodInterceptor failing = call -> {
            if (call.getMethod().getName().equals("save")) {
                throw new IllegalStateException("팀 멤버십 저장 실패");
            }
            return call.proceed();
        };
        Advised repository = (Advised) teamMemberships;
        repository.addAdvice(0, failing);
        try {
            assertThatThrownBy(() -> invitations.accept(mina, UUID.fromString(invitation)))
                    .hasMessage("팀 멤버십 저장 실패");
        }
        finally {
            repository.removeAdvice(failing);
        }

        assertThat(state(invitation)).isEqualTo("PENDING");
        assertThat(count("org_membership WHERE account_id = '" + mina + "'")).isZero();
        assertThat(count("org_activity_log") + count("team_activity_log")).isEqualTo(logs);
    }

    @Test
    @DisplayName("TEN-INVT-18: 거절은 DECLINED와 기록, 기한 전이면 조직 보관 중에도 됨, 기한 지남은 EXPIRED로 바꾸고 409")
    void declinesInvitations() throws Exception {
        UUID mina = newAccount("mina@example.com");
        String invitation = invitation("mina@example.com", "MEMBER", teamId, "TEAM_MEMBER", null);
        assertThat(decline(mina, invitation).getStatus()).isEqualTo(204);
        assertThat(state(invitation)).isEqualTo("DECLINED");
        assertThat(teamLog(teamId, "INVITATION_DECLINED")).containsExactly(mina + " " + invitation
                + " PENDING DECLINED");
        assertThat(code(accept(mina, invitation), 409)).isEqualTo("ALREADY_PROCESSED");

        String archived = invitation("mina@example.com", "MEMBER", null, null, null);
        jdbc.sql("UPDATE organization SET state = 'ARCHIVED' WHERE id = :id").param("id", orgId).update();
        assertThat(decline(mina, archived).getStatus()).isEqualTo(204);
        assertThat(state(archived)).isEqualTo("DECLINED");
        jdbc.sql("UPDATE organization SET state = 'ACTIVE' WHERE id = :id").param("id", orgId).update();

        String overdue = invitation("mina@example.com", "MEMBER", otherTeamId, "TEAM_MEMBER", null);
        jdbc.sql("UPDATE invitation SET accept_by = :past WHERE id = :id")
                .param("past", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).param("id", overdue).update();
        assertThat(code(decline(mina, overdue), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(state(overdue)).isEqualTo("EXPIRED");
        assertThat(count("org_membership WHERE account_id = '" + mina + "'")).isZero();
    }

    @Test
    @DisplayName("TEN-INVT-14: 목록과 첫 화면 초대 수는 수락할 수 있는 초대만 같은 기준으로 셈, 최신부터")
    void listsOnlyAcceptableInvitations() throws Exception {
        UUID mina = newAccount("mina@example.com");
        String older = invitation("mina@example.com", "GUEST", teamId, "VIEWER", 10);
        String newer = invitation("mina@example.com", "MEMBER", null, null, null);
        String overdue = invitation("mina@example.com", "MEMBER", otherTeamId, "TEAM_MEMBER", null);
        jdbc.sql("UPDATE invitation SET accept_by = :past WHERE id = :id")
                .param("past", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).param("id", overdue).update();
        String archivedTeam = createTeam("보관 팀", teamAdmin);
        invitation("mina@example.com", "MEMBER", archivedTeam, "TEAM_MEMBER", null);
        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :id").param("id", archivedTeam).update();
        jdbc.sql("UPDATE organization SET member_admission_review = :on WHERE id = :id").param("on", true)
                .param("id", orgId).update();
        createForTeam(teamAdmin, otherTeamId, invite(List.of("mina@example.com"), "MEMBER", null, "VIEWER", null));
        invitation("sol@example.com", "MEMBER", null, null, null);
        UUID outsider = newAccount("outsider@example.com");
        String foreignOrg = json(createOrg(outsider, "남의 조직"), 201).get("orgId").asString();
        perform(post("/api/orgs/" + foreignOrg + "/invitations").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(
                        invite(List.of("mina@example.com"), "MEMBER", null, null, null))), outsider);
        jdbc.sql("UPDATE organization SET state = 'SUSPENDED' WHERE id = :id").param("id", foreignOrg).update();

        JsonNode items = items(mina);

        assertThat(values(items, "invitationId")).containsExactly(newer, older);
        JsonNode guest = items.get(1);
        assertThat(guest.get("orgName").asString()).isEqualTo("새벽 서재");
        assertThat(guest.get("teamName").asString()).isEqualTo("개발 팀");
        assertThat(guest.get("invitedByName").asString()).isEqualTo("orgadmin");
        assertThat(guest.get("kind").asString()).isEqualTo("GUEST");
        assertThat(guest.get("role").asString()).isEqualTo("VIEWER");
        assertThat(guest.has("acceptBy")).isTrue();
        assertThat(guest.has("email")).isFalse();
        assertThat(items.get(0).get("teamName").isNull()).isTrue();
        assertThat(json(perform(get("/api/me/home"), mina), 200).get("invitationCount").asInt()).isEqualTo(2);
        assertThat(json(perform(get("/api/me/home"), orgAdmin), 200).get("invitationCount").asInt()).isZero();

        assertThat(decline(mina, newer).getStatus()).isEqualTo(204);
        assertThat(values(items(mina), "invitationId")).containsExactly(older);
        assertThat(json(perform(get("/api/me/home"), mina), 200).get("invitationCount").asInt()).isOne();
    }

    @Test
    @DisplayName("TEN-INVT-15: 같은 초대를 동시에 수락하면 하나만 처리, 같은 조직 초대 둘을 동시에 수락해도 조직 멤버십은 하나")
    void concurrentAcceptance() throws Exception {
        UUID mina = newAccount("mina@example.com");
        String same = invitation("mina@example.com", "MEMBER", teamId, "TEAM_MEMBER", null);
        List<Object> results = race(() -> invitations.accept(mina, UUID.fromString(same)),
                () -> invitations.accept(mina, UUID.fromString(same)));
        assertThat(results).filteredOn(Acceptance.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ReberuException.class::isInstance)
                .allMatch(failed -> ((ReberuException) failed).code() == ErrorCode.ALREADY_PROCESSED)
                .hasSize(1);

        UUID sol = newAccount("sol@example.com");
        String orgOnly = invitation("sol@example.com", "MEMBER", null, null, null);
        String team = invitation("sol@example.com", "MEMBER", otherTeamId, "VIEWER", null);
        List<Object> both = race(() -> invitations.accept(sol, UUID.fromString(orgOnly)),
                () -> invitations.accept(sol, UUID.fromString(team)));
        assertThat(both).allMatch(Acceptance.class::isInstance);
        assertThat(count("org_membership WHERE account_id = '" + sol + "'")).isOne();
        assertThat(orgLog("ORG_MEMBER_JOINED")).filteredOn(line -> line.startsWith(sol.toString())).hasSize(1);
        assertThat(teamMember(otherTeamId, sol)).isEqualTo("ACTIVE VIEWER INVITATION null");
    }

    @SafeVarargs
    private List<Object> race(java.util.function.Supplier<Object>... tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        for (java.util.function.Supplier<Object> task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return task.get();
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return interrupted;
                }
                catch (RuntimeException failed) {
                    return failed;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (CompletableFuture<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private String invitation(String email, String kind, String team, String role, Integer days) throws Exception {
        return invitationId(createForOrg(orgAdmin, invite(List.of(email), kind, team, role, days)));
    }

    private JsonNode items(UUID account) throws Exception {
        return json(perform(get("/api/me/invitations"), account), 200).get("items");
    }

    private MockHttpServletResponse accept(UUID account, String invitation) throws Exception {
        return perform(post("/api/me/invitations/" + invitation + "/accept"), account);
    }

    private MockHttpServletResponse decline(UUID account, String invitation) throws Exception {
        return perform(post("/api/me/invitations/" + invitation + "/decline"), account);
    }

    private String orgMember(UUID account) {
        return jdbc.sql("""
                        SELECT state, kind, joined_via, org_admin, expires_at FROM org_membership
                        WHERE org_id = :org AND account_id = :account
                        """)
                .param("org", orgId).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + " " + rs.getString("kind") + " "
                        + rs.getString("joined_via") + " " + rs.getBoolean("org_admin") + " "
                        + (rs.getObject("expires_at") == null ? "null" : "expires"))
                .single();
    }

    private String teamMember(String team, UUID account) {
        return jdbc.sql("SELECT state, role, joined_via, expires_at FROM team_membership "
                        + "WHERE team_id = :team AND account_id = :account")
                .param("team", team).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + " " + rs.getString("role") + " "
                        + rs.getString("joined_via") + " "
                        + (rs.getObject("expires_at") == null ? "null" : "expires"))
                .single();
    }

    private LocalDateTime orgExpiresAt(UUID account) {
        return jdbc.sql("SELECT expires_at FROM org_membership WHERE org_id = :org AND account_id = :account")
                .param("org", orgId).param("account", account.toString()).query(LocalDateTime.class).single();
    }

    private LocalDateTime teamExpiresAt(String team, UUID account) {
        return jdbc.sql("SELECT expires_at FROM team_membership WHERE team_id = :team AND account_id = :account")
                .param("team", team).param("account", account.toString()).query(LocalDateTime.class).single();
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

    private String invitationId(MockHttpServletResponse response) throws Exception {
        List<String> ids = values(json(response, 201).get("invitations"), "invitationId");
        assertThat(ids).hasSize(1);
        return ids.get(0);
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

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tableAndWhere).query(Long.class).single();
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

    private void insertOrgMembership(UUID account, String kind, String state, LocalDateTime expiresAt) {
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
                .param("expires", expiresAt)
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String team, UUID account, String role, String state,
            LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, expires_at,
                            created_at, updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'INVITATION', :expires, :now, :now, 0)
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

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private static List<String> values(JsonNode items, String field) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.get(field).isNull() ? null : item.get(field).asString()));
        return values;
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
