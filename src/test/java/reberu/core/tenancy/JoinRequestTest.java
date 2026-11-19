package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class JoinRequestTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    private UUID orgAdmin;
    private UUID member;
    private UUID outsider;
    private String orgId;
    private String publicTeam;
    private String privateTeam;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : InviteLinkTest.TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        orgAdmin = newAccount("orgadmin@example.com");
        member = newAccount("haneul@example.com");
        outsider = newAccount("outsider@example.com");
        orgId = createOrg(orgAdmin, "새벽 서재");
        openOrg(orgId, "APPROVAL");
        insertOrgMembership(orgId, member, "MEMBER", "ACTIVE", null);
        publicTeam = createTeam("독서 모임", "PUBLIC");
        privateTeam = createTeam("운영 팀", "PRIVATE");
    }

    @Test
    @DisplayName("§7.5·TEN-REQ-04·09: 승인 필요 조직에 요청하면 REQUESTED·확인 대기, A2로, 다시 보내도 그대로")
    void approvalOrgRequests() throws Exception {
        JsonNode requested = json(joinOrg(outsider, orgId), 200);

        assertThat(requested.get("result").asString()).isEqualTo("REQUESTED");
        assertThat(requested.get("next").get("screen").asString()).isEqualTo("JOIN_PENDING");
        assertThat(orgMembership(orgId, outsider)).isEqualTo("REQUESTED MEMBER REQUEST true");
        assertThat(log("org_activity_log", "ORG_MEMBER_REQUESTED", outsider)).containsExactly("null REQUESTED");

        assertThat(json(joinOrg(outsider, orgId), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(log("org_activity_log", "ORG_MEMBER_REQUESTED", outsider)).hasSize(1);
        assertThat(json(perform(get("/api/me/home"), outsider), 200).get("next").get("screen").asString())
                .isEqualTo("JOIN_PENDING");
    }

    @Test
    @DisplayName("§7.5·T-ORG-OPEN·SEC-ORG-11: 바로 가입은 구성원이 되지만 팀 데이터는 열지 않고 공개 팀 목록만 보임")
    void instantJoinDoesNotOpenTeams() throws Exception {
        openOrg(orgId, "INSTANT");

        JsonNode joined = json(joinOrg(outsider, orgId), 200);

        assertThat(joined.get("result").asString()).isEqualTo("JOINED");
        assertThat(joined.get("next").get("screen").asString()).isEqualTo("TEAM_PICKER");
        assertThat(orgMembership(orgId, outsider)).isEqualTo("ACTIVE MEMBER REQUEST false");
        assertThat(teamMemberships(outsider)).isZero();
        assertThat(log("org_activity_log", "ORG_MEMBER_JOINED", outsider)).containsExactly("null MEMBER");
        JsonNode teams = json(perform(get("/api/orgs/" + orgId + "/teams"), outsider), 200);
        List<String> names = new ArrayList<>();
        teams.get("items").forEach(item -> names.add(item.get("name").asString()));
        assertThat(names).containsExactly("독서 모임");
        for (String team : List.of(publicTeam, privateTeam)) {
            assertThat(perform(get("/api/teams/" + team), outsider).getStatus()).isEqualTo(404);
            assertThat(perform(get("/api/teams/" + team + "/members"), outsider).getStatus()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("TEN-REQ-04: 이미 구성원이면 JOINED, 아무것도 바꾸지 않음. 정지·ACTIVE 손님(기한 지남 포함)은 404")
    void currentMembershipsAreKept() throws Exception {
        assertThat(json(joinOrg(member, orgId), 200).get("result").asString()).isEqualTo("JOINED");
        assertThat(orgMembership(orgId, member)).isEqualTo("ACTIVE MEMBER INVITATION false");
        assertThat(log("org_activity_log", "ORG_MEMBER_JOINED", member)).isEmpty();

        UUID suspended = newAccount("suspended@example.com");
        insertOrgMembership(orgId, suspended, "MEMBER", "SUSPENDED", null);
        UUID guest = newAccount("guest@example.com");
        insertOrgMembership(orgId, guest, "GUEST", "ACTIVE", LocalDateTime.now(ZoneOffset.UTC).plusDays(3));
        UUID lapsed = newAccount("lapsed@example.com");
        insertOrgMembership(orgId, lapsed, "GUEST", "ACTIVE", LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        String hidden = joinOrg(outsider, UUID.randomUUID().toString()).getContentAsString();
        for (UUID account : List.of(suspended, guest, lapsed)) {
            MockHttpServletResponse response = joinOrg(account, orgId);
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(withoutTraceId(response.getContentAsString())).isEqualTo(withoutTraceId(hidden));
        }
        assertThat(orgMembership(orgId, suspended)).startsWith("SUSPENDED");
        assertThat(orgMembership(orgId, guest)).startsWith("ACTIVE GUEST");
    }

    @Test
    @DisplayName("T-ORG-HIDDEN·API-ORG-01·SEC-ORG-01: 없음·비공개·요청 꺼짐·보관·정지 조직의 가입은 같은 404, 검색에도 안 나옴")
    void hiddenOrgsLookTheSame() throws Exception {
        String privateOrg = createOrg(orgAdmin, "비밀 정원");
        jdbc.sql("UPDATE organization SET join_request_mode = 'INSTANT' WHERE id = :org").param("org", privateOrg)
                .update();
        String closedOrg = createOrg(orgAdmin, "닫힌 정원");
        jdbc.sql("UPDATE organization SET visibility = 'PUBLIC' WHERE id = :org").param("org", closedOrg).update();
        String archivedOrg = createOrg(orgAdmin, "잠든 정원");
        openOrg(archivedOrg, "INSTANT");
        setOrgState(archivedOrg, "ARCHIVED");
        String suspendedOrg = createOrg(orgAdmin, "멈춘 정원");
        openOrg(suspendedOrg, "INSTANT");
        setOrgState(suspendedOrg, "SUSPENDED");
        String expected = withoutTraceId(joinOrg(outsider, UUID.randomUUID().toString()).getContentAsString());

        for (String org : List.of(privateOrg, closedOrg, archivedOrg, suspendedOrg)) {
            MockHttpServletResponse response = joinOrg(outsider, org);
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(withoutTraceId(response.getContentAsString())).isEqualTo(expected);
            assertThat(response.getContentAsString()).doesNotContain("정원");
            assertThat(orgMembership(org, outsider)).isNull();
        }
        assertThat(searchNames(outsider, "정원")).isEmpty();
        assertThat(searchNames(outsider, "서재")).containsExactly("새벽 서재");
    }

    @Test
    @DisplayName("TEN-REQ-13·SEC-ORG-03: 검색은 대소문자 무시 부분 일치, %·_는 글자 그대로, 이름순 20개, 정해진 필드만")
    void searchMatchesNamesOnly() throws Exception {
        String upper = createOrg(newAccount("morning@example.com"), "Morning Club");
        openOrg(upper, "INSTANT");
        String percent = createOrg(newAccount("percent@example.com"), "100% 모임");
        openOrg(percent, "APPROVAL");
        for (int i = 0; i < 22; i++) {
            String name = String.format("숲길 %02d", i);
            openOrg(createOrg(newAccount("forest" + i + "@example.com"), name), "APPROVAL");
        }

        JsonNode result = json(search(outsider, "  morning "), 200);
        assertThat(result.get("items")).hasSize(1);
        JsonNode item = result.get("items").get(0);
        assertThat(item.get("orgId").asString()).isEqualTo(upper);
        assertThat(item.get("joinRequestMode").asString()).isEqualTo("INSTANT");
        List<String> fields = new ArrayList<>();
        item.propertyNames().forEach(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("orgId", "name", "tagline", "joinRequestMode");
        assertThat(searchNames(outsider, "0%")).containsExactly("100% 모임");
        assertThat(searchNames(outsider, "%%")).isEmpty();
        assertThat(searchNames(outsider, "_0")).isEmpty();
        List<String> forest = searchNames(outsider, "숲길");
        assertThat(forest).hasSize(20).first().isEqualTo("숲길 00");
        assertThat(forest).last().isEqualTo("숲길 19");
        assertThat(search(outsider, "서").getStatus()).isEqualTo(400);
        assertThat(search(outsider, "가".repeat(41)).getStatus()).isEqualTo(400);
        assertThat(perform(get("/api/public-orgs"), outsider).getStatus()).isEqualTo(400);
        assertThat(json(search(outsider, " 서 "), 400).get("fields").get(0).get("reason").asString())
                .isEqualTo("TOO_SHORT");
    }

    @Test
    @DisplayName("TEN-REQ-02·07: 거절 뒤 7일 안의 요청은 409 REQUEST_COOLDOWN, 지나면 다시 REQUESTED")
    void rejectedRequestCoolsDown() throws Exception {
        UUID recent = newAccount("recent@example.com");
        insertOrgMembership(orgId, recent, "MEMBER", "REJECTED", null);
        rejectedAt("org_membership", "org_id", orgId, recent, Instant.now().minusSeconds(6 * 86400));
        UUID old = newAccount("old@example.com");
        insertOrgMembership(orgId, old, "MEMBER", "REJECTED", null);
        rejectedAt("org_membership", "org_id", orgId, old, Instant.now().minusSeconds(8 * 86400));

        MockHttpServletResponse cooling = joinOrg(recent, orgId);
        assertThat(json(cooling, 409).get("code").asString()).isEqualTo("REQUEST_COOLDOWN");
        assertThat(orgMembership(orgId, recent)).startsWith("REJECTED");

        assertThat(json(joinOrg(old, orgId), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(orgMembership(orgId, old)).isEqualTo("REQUESTED MEMBER INVITATION true");
        assertThat(log("org_activity_log", "ORG_MEMBER_REQUESTED", old)).containsExactly("REJECTED REQUESTED");
    }

    @Test
    @DisplayName("TEN-REQ-05: 바로 가입 조직·팀이어도 내보내진 사람은 REQUESTED, 스스로 나간 사람은 바로 들어옴")
    void removedPeopleMustRequest() throws Exception {
        openOrg(orgId, "INSTANT");
        UUID removed = newAccount("removed@example.com");
        insertOrgMembership(orgId, removed, "MEMBER", "LEFT", null);
        leftReason("org_membership", "org_id", orgId, removed, "REMOVED");
        UUID self = newAccount("self@example.com");
        insertOrgMembership(orgId, self, "MEMBER", "LEFT", null);
        leftReason("org_membership", "org_id", orgId, self, "SELF");

        assertThat(json(joinOrg(removed, orgId), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(orgMembership(orgId, removed)).isEqualTo("REQUESTED MEMBER INVITATION true");
        assertThat(json(joinOrg(self, orgId), 200).get("result").asString()).isEqualTo("JOINED");
        assertThat(orgMembership(orgId, self)).isEqualTo("ACTIVE MEMBER INVITATION false");

        setTeamJoinMode(publicTeam, "INSTANT");
        insertTeamMembership(publicTeam, member, "VIEWER", "LEFT");
        leftReason("team_membership", "team_id", publicTeam, member, "REMOVED");
        JsonNode team = json(joinTeam(member, publicTeam), 200);
        assertThat(team.get("result").asString()).isEqualTo("REQUESTED");
        assertThat(teamMembership(publicTeam, member)).isEqualTo("REQUESTED TEAM_MEMBER INVITATION");
    }

    @Test
    @DisplayName("§7.5 공개 팀 가입: 승인 필요는 REQUESTED·A2, 바로는 ACTIVE·TEAM_MEMBER·팀 첫 화면, 다시 보내도 그대로")
    void publicTeamJoin() throws Exception {
        JsonNode requested = json(joinTeam(member, publicTeam), 200);
        assertThat(requested.get("result").asString()).isEqualTo("REQUESTED");
        assertThat(requested.get("next").get("screen").asString()).isEqualTo("JOIN_PENDING");
        assertThat(teamMembership(publicTeam, member)).isEqualTo("REQUESTED TEAM_MEMBER REQUEST");
        assertThat(log("team_activity_log", "TEAM_MEMBER_REQUESTED", member)).containsExactly("null TEAM_MEMBER");
        assertThat(json(joinTeam(member, publicTeam), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(log("team_activity_log", "TEAM_MEMBER_REQUESTED", member)).hasSize(1);

        setTeamJoinMode(publicTeam, "INSTANT");
        UUID other = newAccount("other@example.com");
        insertOrgMembership(orgId, other, "MEMBER", "ACTIVE", null);
        JsonNode joined = json(joinTeam(other, publicTeam), 200);
        assertThat(joined.get("result").asString()).isEqualTo("JOINED");
        assertThat(joined.get("next").get("screen").asString()).isEqualTo("TEAM_HOME");
        assertThat(joined.get("next").get("teamId").asString()).isEqualTo(publicTeam);
        assertThat(teamMembership(publicTeam, other)).isEqualTo("ACTIVE TEAM_MEMBER REQUEST");
        assertThat(perform(get("/api/teams/" + publicTeam + "/members"), other).getStatus()).isEqualTo(200);

        assertThat(json(joinTeam(other, publicTeam), 200).get("result").asString()).isEqualTo("JOINED");
        assertThat(log("team_activity_log", "TEAM_MEMBER_JOINED", other)).containsExactly("null TEAM_MEMBER");
    }

    @Test
    @DisplayName("§7.5 공개 팀 가입의 404: 조직 밖·요청 중·손님·비공개 팀·보관된 팀·팀 멤버십 정지. 보관된 조직은 409 READ_ONLY")
    void teamJoinFailures() throws Exception {
        UUID requested = newAccount("requested@example.com");
        insertOrgMembership(orgId, requested, "MEMBER", "REQUESTED", null);
        UUID guest = newAccount("guest@example.com");
        insertOrgMembership(orgId, guest, "GUEST", "ACTIVE", LocalDateTime.now(ZoneOffset.UTC).plusDays(3));
        for (UUID account : List.of(outsider, requested, guest)) {
            assertThat(joinTeam(account, publicTeam).getStatus()).isEqualTo(404);
        }
        assertThat(joinTeam(member, privateTeam).getStatus()).isEqualTo(404);
        assertThat(joinTeam(member, UUID.randomUUID().toString()).getStatus()).isEqualTo(404);
        UUID suspended = newAccount("suspended@example.com");
        insertOrgMembership(orgId, suspended, "MEMBER", "ACTIVE", null);
        insertTeamMembership(publicTeam, suspended, "TEAM_MEMBER", "SUSPENDED");
        assertThat(joinTeam(suspended, publicTeam).getStatus()).isEqualTo(404);
        String archived = createTeam("쉬는 팀", "PUBLIC");
        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :team").param("team", archived).update();
        assertThat(joinTeam(member, archived).getStatus()).isEqualTo(404);

        setOrgState(orgId, "ARCHIVED");
        assertThat(json(joinTeam(member, publicTeam), 409).get("code").asString()).isEqualTo("READ_ONLY");
        assertThat(teamMembership(publicTeam, member)).isNull();
        assertThat(teamMembership(publicTeam, requested)).isNull();
    }

    @Test
    @DisplayName("TEN-REQ-07: 팀 거절 뒤 7일 안의 팀 요청은 409 REQUEST_COOLDOWN, 지나면 다시 요청")
    void rejectedTeamRequestCoolsDown() throws Exception {
        insertTeamMembership(publicTeam, member, "TEAM_MEMBER", "REJECTED");
        rejectedAt("team_membership", "team_id", publicTeam, member, Instant.now().minusSeconds(86400));

        assertThat(json(joinTeam(member, publicTeam), 409).get("code").asString()).isEqualTo("REQUEST_COOLDOWN");

        rejectedAt("team_membership", "team_id", publicTeam, member, Instant.now().minusSeconds(8 * 86400));
        assertThat(json(joinTeam(member, publicTeam), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(log("team_activity_log", "TEAM_MEMBER_REQUESTED", member)).containsExactly("REJECTED TEAM_MEMBER");
    }

    @Test
    @DisplayName("TEN-TEAM-07·TEN-SET-05·TEN-REQ-07: 팀·조직 보관은 REQUESTED를 거절하되 거절 시각을 비워 복원 뒤 바로 다시 요청")
    void archiveRejectsWithoutCooldown() throws Exception {
        json(joinTeam(member, publicTeam), 200);

        assertThat(perform(post("/api/orgs/" + orgId + "/teams/" + publicTeam + "/archive"), orgAdmin).getStatus())
                .isEqualTo(204);

        assertThat(teamMembership(publicTeam, member)).startsWith("REJECTED");
        assertThat(rejectedAtOf("team_membership", "team_id", publicTeam, member)).isNull();
        assertThat(jdbc.sql("""
                        SELECT before_value, after_value FROM team_activity_log
                        WHERE team_id = :team AND action = 'TEAM_MEMBER_REJECTED' AND target_id = :account
                        """)
                .param("team", publicTeam).param("account", member.toString())
                .query((rs, rowNum) -> rs.getString(1) + " " + rs.getString(2)).list())
                .containsExactly("REQUESTED REJECTED");
        assertThat(perform(post("/api/orgs/" + orgId + "/teams/" + publicTeam + "/restore"), orgAdmin).getStatus())
                .isEqualTo(204);
        assertThat(json(joinTeam(member, publicTeam), 200).get("result").asString()).isEqualTo("REQUESTED");

        json(joinOrg(outsider, orgId), 200);
        assertThat(perform(post("/api/orgs/" + orgId + "/archive"), orgAdmin).getStatus()).isEqualTo(204);
        assertThat(orgMembership(orgId, outsider)).isEqualTo("REJECTED MEMBER REQUEST false");
        assertThat(rejectedAtOf("org_membership", "org_id", orgId, outsider)).isNull();
        assertThat(rejectedAtOf("team_membership", "team_id", publicTeam, member)).isNull();
        assertThat(perform(post("/api/orgs/" + orgId + "/restore"), orgAdmin).getStatus()).isEqualTo(204);
        assertThat(json(joinOrg(outsider, orgId), 200).get("result").asString()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("TEN-REQ-10: 내 요청 목록은 REQUESTED와 대기 중 REJECTED만, 처리될 수 없는 조직·팀 요청은 빼고 최신순")
    void listsMyRequests() throws Exception {
        json(joinOrg(outsider, orgId), 200);
        String otherOrg = createOrg(orgAdmin, "저녁 정원");
        insertOrgMembership(otherOrg, outsider, "MEMBER", "REJECTED", null);
        Instant rejected = Instant.now().minusSeconds(2 * 86400);
        rejectedAt("org_membership", "org_id", otherOrg, outsider, rejected);
        String oldOrg = createOrg(orgAdmin, "지난 정원");
        insertOrgMembership(oldOrg, outsider, "MEMBER", "REJECTED", null);
        rejectedAt("org_membership", "org_id", oldOrg, outsider, Instant.now().minusSeconds(9 * 86400));
        String archivedOrg = createOrg(orgAdmin, "보관 정원");
        insertOrgMembership(archivedOrg, outsider, "MEMBER", "REQUESTED", null);
        setOrgState(archivedOrg, "ARCHIVED");
        json(joinTeam(member, publicTeam), 200);
        insertTeamMembership(privateTeam, member, "TEAM_MEMBER", "REQUESTED");
        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :team").param("team", privateTeam).update();

        JsonNode mine = json(perform(get("/api/me/join-requests"), outsider), 200).get("items");
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).get("target").asString()).isEqualTo("ORG");
        assertThat(mine.get(0).get("orgId").asString()).isEqualTo(orgId);
        assertThat(mine.get(0).get("orgName").asString()).isEqualTo("새벽 서재");
        assertThat(mine.get(0).get("state").asString()).isEqualTo("REQUESTED");
        assertThat(mine.get(0).get("requestedAt").isNull()).isFalse();
        assertThat(mine.get(0).get("retryAt").isNull()).isTrue();
        assertThat(mine.get(1).get("orgName").asString()).isEqualTo("저녁 정원");
        assertThat(mine.get(1).get("state").asString()).isEqualTo("REJECTED");
        assertThat(Instant.parse(mine.get(1).get("retryAt").asString()))
                .isBetween(rejected.plusSeconds(7 * 86400 - 1), rejected.plusSeconds(7 * 86400 + 1));

        JsonNode team = json(perform(get("/api/me/join-requests"), member), 200).get("items");
        assertThat(team).hasSize(1);
        assertThat(team.get(0).get("target").asString()).isEqualTo("TEAM");
        assertThat(team.get(0).get("teamId").asString()).isEqualTo(publicTeam);
        assertThat(team.get(0).get("teamName").asString()).isEqualTo("독서 모임");
        assertThat(json(perform(get("/api/me/join-requests"), orgAdmin), 200).get("items")).isEmpty();
    }

    @Test
    @DisplayName("TEN-REQ-11: 조직 요청 취소는 그 조직의 내 팀 요청도 LEFT·SELF, 다시 취소하면 409, 남의 것·없는 것은 404")
    void cancelsOrgRequest() throws Exception {
        String token = linkToken(true);
        assertThat(json(redeem(outsider, token), 200).get("result").asString()).isEqualTo("REQUESTED");

        assertThat(perform(post("/api/me/org-requests/" + orgId + "/cancel"), outsider).getStatus()).isEqualTo(204);

        assertThat(orgMembership(orgId, outsider)).isEqualTo("LEFT MEMBER LINK false");
        assertThat(leftReasonOf("org_membership", "org_id", orgId, outsider)).isEqualTo("SELF");
        assertThat(teamMembership(publicTeam, outsider)).startsWith("LEFT");
        assertThat(leftReasonOf("team_membership", "team_id", publicTeam, outsider)).isEqualTo("SELF");
        assertThat(log("org_activity_log", "ORG_MEMBER_REQUEST_CANCELLED", outsider))
                .containsExactly("REQUESTED LEFT");
        assertThat(log("team_activity_log", "TEAM_MEMBER_REQUEST_CANCELLED", outsider))
                .containsExactly("REQUESTED LEFT");
        assertThat(json(perform(post("/api/me/org-requests/" + orgId + "/cancel"), outsider), 409).get("code")
                .asString()).isEqualTo("ALREADY_PROCESSED");
        assertThat(json(perform(post("/api/me/org-requests/" + orgId + "/cancel"), member), 409).get("code")
                .asString()).isEqualTo("ALREADY_PROCESSED");
        UUID stranger = newAccount("stranger@example.com");
        assertThat(perform(post("/api/me/org-requests/" + orgId + "/cancel"), stranger).getStatus()).isEqualTo(404);
        assertThat(perform(post("/api/me/org-requests/" + UUID.randomUUID() + "/cancel"), stranger).getStatus())
                .isEqualTo(404);
        assertThat(json(joinOrg(outsider, orgId), 200).get("result").asString()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("TEN-REQ-11: 팀 요청 취소는 그 팀 멤버십만 LEFT, 요청 중이 아니면 409, 없으면 404")
    void cancelsTeamRequest() throws Exception {
        json(joinTeam(member, publicTeam), 200);

        assertThat(perform(post("/api/me/team-requests/" + publicTeam + "/cancel"), member).getStatus())
                .isEqualTo(204);

        assertThat(teamMembership(publicTeam, member)).startsWith("LEFT");
        assertThat(orgMembership(orgId, member)).startsWith("ACTIVE");
        assertThat(log("team_activity_log", "TEAM_MEMBER_REQUEST_CANCELLED", member))
                .containsExactly("REQUESTED LEFT");
        assertThat(perform(post("/api/me/team-requests/" + publicTeam + "/cancel"), member).getStatus())
                .isEqualTo(409);
        assertThat(perform(post("/api/me/team-requests/" + publicTeam + "/cancel"), outsider).getStatus())
                .isEqualTo(404);
        assertThat(perform(post("/api/me/team-requests/" + UUID.randomUUID() + "/cancel"), member).getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("TEN-REQ-09: 조직 관리자 승인 필요 링크는 확인 대기 아님, 팀 관리자 링크+TEN-ADM-01이 오면 확인 대기로 켜짐")
    void linkRequestsMarkAdmissionReview() throws Exception {
        String orgAdminLink = linkToken(true);
        assertThat(json(redeem(outsider, orgAdminLink), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(orgMembership(orgId, outsider)).isEqualTo("REQUESTED MEMBER LINK false");

        UUID teamAdmin = newAccount("teamadmin@example.com");
        insertOrgMembership(orgId, teamAdmin, "MEMBER", "ACTIVE", null);
        insertTeamMembership(privateTeam, teamAdmin, "TEAM_ADMIN", "ACTIVE");
        String teamAdminLink = linkToken(privateTeam, teamAdmin, false);
        assertThat(json(redeem(outsider, teamAdminLink), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(orgMembership(orgId, outsider)).isEqualTo("REQUESTED MEMBER LINK true");

        UUID another = newAccount("another@example.com");
        json(joinOrg(another, orgId), 200);
        assertThat(json(redeem(another, orgAdminLink), 200).get("result").asString()).isEqualTo("REQUESTED");
        assertThat(orgMembership(orgId, another)).isEqualTo("REQUESTED MEMBER REQUEST true");
    }

    @Test
    @DisplayName("TEN-REQ-01·08: 같은 계정의 동시 가입 요청 둘은 멤버십 하나·기록 하나")
    void concurrentRequestsMakeOne() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> request = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    return -1;
                }
                return joinOrg(outsider, orgId).getStatus();
            };
            List<Future<Integer>> results = List.of(executor.submit(request), executor.submit(request));
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
        finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM org_membership WHERE account_id = :account")
                .param("account", outsider.toString()).query(Integer.class).single()).isOne();
        assertThat(log("org_activity_log", "ORG_MEMBER_REQUESTED", outsider)).hasSize(1);
    }

    private MockHttpServletResponse joinOrg(UUID actor, String org) throws Exception {
        return perform(post("/api/public-orgs/" + org + "/join"), actor);
    }

    private MockHttpServletResponse joinTeam(UUID actor, String team) throws Exception {
        return perform(post("/api/orgs/" + orgId + "/teams/" + team + "/join"), actor);
    }

    private MockHttpServletResponse search(UUID actor, String query) throws Exception {
        return perform(get("/api/public-orgs").param("q", query), actor);
    }

    private List<String> searchNames(UUID actor, String query) throws Exception {
        List<String> names = new ArrayList<>();
        json(search(actor, query), 200).get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    private MockHttpServletResponse redeem(UUID actor, String token) throws Exception {
        return perform(post("/api/invite-links/redeem").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("token", token))), actor);
    }

    private String linkToken(boolean approvalRequired) throws Exception {
        return linkToken(publicTeam, orgAdmin, approvalRequired);
    }

    private String linkToken(String team, UUID creator, boolean approvalRequired) throws Exception {
        jdbc.sql("UPDATE organization SET join_link_enabled = :on WHERE id = :org").param("on", true)
                .param("org", orgId).update();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvalRequired", approvalRequired);
        body.put("role", "TEAM_MEMBER");
        body.put("maxUses", 10);
        String url = json(perform(post("/api/teams/" + team + "/invite-links")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), creator), 201)
                .get("url").asString();
        return url.substring(url.indexOf('#') + 1);
    }

    private void openOrg(String org, String mode) {
        jdbc.sql("UPDATE organization SET visibility = 'PUBLIC', join_request_mode = :mode WHERE id = :org")
                .param("mode", mode).param("org", org).update();
    }

    private void setOrgState(String org, String state) {
        jdbc.sql("UPDATE organization SET state = :state WHERE id = :org").param("state", state).param("org", org)
                .update();
    }

    private void setTeamJoinMode(String team, String mode) {
        jdbc.sql("UPDATE team SET join_mode = :mode WHERE id = :team").param("mode", mode).param("team", team)
                .update();
    }

    private void rejectedAt(String table, String scope, String scopeId, UUID account, Instant at) {
        jdbc.sql("UPDATE " + table + " SET rejected_at = :at WHERE " + scope + " = :scope AND account_id = :account")
                .param("at", LocalDateTime.ofInstant(at, ZoneOffset.UTC)).param("scope", scopeId)
                .param("account", account.toString()).update();
    }

    private LocalDateTime rejectedAtOf(String table, String scope, String scopeId, UUID account) {
        return jdbc.sql("SELECT rejected_at FROM " + table + " WHERE " + scope + " = :scope AND account_id = :account")
                .param("scope", scopeId).param("account", account.toString())
                .query((rs, rowNum) -> rs.getObject(1, LocalDateTime.class)).list().get(0);
    }

    private void leftReason(String table, String scope, String scopeId, UUID account, String reason) {
        jdbc.sql("UPDATE " + table + " SET left_reason = :reason WHERE " + scope + " = :scope AND account_id = :account")
                .param("reason", reason).param("scope", scopeId).param("account", account.toString()).update();
    }

    private String leftReasonOf(String table, String scope, String scopeId, UUID account) {
        return jdbc.sql("SELECT left_reason FROM " + table + " WHERE " + scope + " = :scope AND account_id = :account")
                .param("scope", scopeId).param("account", account.toString()).query(String.class).single();
    }

    private String orgMembership(String org, UUID account) {
        return jdbc.sql("""
                        SELECT state, kind, joined_via, admission_review FROM org_membership
                        WHERE org_id = :org AND account_id = :account
                        """)
                .param("org", org).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + " " + rs.getString("kind") + " "
                        + rs.getString("joined_via") + " " + rs.getBoolean("admission_review"))
                .optional().orElse(null);
    }

    private String teamMembership(String team, UUID account) {
        return jdbc.sql("""
                        SELECT state, role, joined_via FROM team_membership
                        WHERE team_id = :team AND account_id = :account
                        """)
                .param("team", team).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + " " + rs.getString("role") + " "
                        + rs.getString("joined_via"))
                .optional().orElse(null);
    }

    private int teamMemberships(UUID account) {
        return jdbc.sql("SELECT COUNT(*) FROM team_membership WHERE account_id = :account")
                .param("account", account.toString()).query(Integer.class).single();
    }

    private List<String> log(String table, String action, UUID actor) {
        return jdbc.sql("SELECT before_value, after_value FROM " + table
                        + " WHERE action = :action AND actor_id = :actor ORDER BY occurred_at, id")
                .param("action", action).param("actor", actor.toString())
                .query((rs, rowNum) -> rs.getString("before_value") + " " + rs.getString("after_value"))
                .list();
    }

    private void insertOrgMembership(String org, UUID account, String kind, String state, LocalDateTime expires) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version, admission_review)
                        VALUES (:id, :org, :account, :kind, :admin, :state, 'INVITATION', :expires, :now, :now, 0, FALSE)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", org)
                .param("account", account.toString())
                .param("kind", kind)
                .param("admin", false)
                .param("state", state)
                .param("expires", expires)
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String team, UUID account, String role, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, created_at,
                            updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'INVITATION', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", team)
                .param("account", account.toString())
                .param("role", role)
                .param("state", state)
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

    private static String withoutTraceId(String body) {
        return body.replaceAll("\"traceId\":\"[^\"]*\"", "");
    }

    private UUID newAccount(String email) {
        String name = email.substring(0, email.indexOf('@')).toLowerCase();
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(), email, name,
                null, null), "v1", "v1", Instant.now());
    }

}
