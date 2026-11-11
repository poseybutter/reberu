package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberTest {

    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "team_membership",
            "team", "job_type", "org_membership", "organization", "api_idempotency_key", "auth_session",
            "account_consent", "account");

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
        JsonNode created = json(createOrg(admin, "새벽 서재"), 201);
        orgId = created.get("orgId").asString();
        firstTeamId = created.get("teamId").asString();
        insertOrgMembership(orgId, member, "MEMBER", "ACTIVE", false);
        insertOrgMembership(orgId, guest, "GUEST", "ACTIVE", false);
        insertTeamMembership(firstTeamId, member, "TEAM_MEMBER", "ACTIVE");
    }

    @Test
    @DisplayName("구성원 목록: 기본은 ACTIVE·SUSPENDED·EXPIRED, 이메일·팀·마지막 접속, 상태·종류·팀으로 거르기")
    void listsMembers() throws Exception {
        UUID left = newAccount("left");
        insertOrgMembership(orgId, left, "MEMBER", "LEFT", false);

        JsonNode page = json(perform(get(path("/members")), admin), 200);
        JsonNode first = page.get("items").get(0);

        assertThat(accountIds(page)).containsExactly(admin.toString(), member.toString(), guest.toString());
        assertThat(first.get("email").asString()).isEqualTo("admin@example.com");
        assertThat(first.get("orgAdmin").asBoolean()).isTrue();
        assertThat(first.get("teams").get(0).get("role").asString()).isEqualTo("TEAM_ADMIN");
        assertThat(first.get("teams").get(0).get("name").asString()).isEqualTo("첫 팀");
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(accountIds(json(perform(get(path("/members?kind=GUEST")), admin), 200)))
                .containsExactly(guest.toString());
        assertThat(accountIds(json(perform(get(path("/members?state=LEFT")), admin), 200)))
                .containsExactly(left.toString());
        assertThat(accountIds(json(perform(get(path("/members?teamId=" + firstTeamId)), admin), 200)))
                .containsExactly(admin.toString(), member.toString());
        assertThat(accountIds(json(perform(get(path("/members?teamId=" + UUID.randomUUID())), admin), 200)))
                .isEmpty();
        assertThat(fields(perform(get(path("/members?state=REQUESTED")), admin)))
                .containsExactly("state:NOT_ALLOWED_VALUE");
        assertThat(perform(get(path("/members?state=NOPE")), admin).getStatus()).isEqualTo(400);
        assertThat(perform(get(path("/members")), member).getStatus()).isEqualTo(403);
        assertThat(perform(get(path("/members")), guest).getStatus()).isEqualTo(404);
        assertThat(perform(get(path("/members")), outsider).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("API-PAGE-01: 구성원 목록 커서로 빠짐없이, 고친 커서·다른 조직 커서는 400")
    void pagesMembers() throws Exception {
        JsonNode first = json(perform(get(path("/members?limit=2")), admin), 200);
        String cursor = first.get("nextCursor").asString();
        JsonNode second = json(perform(get(path("/members?limit=2&cursor=" + cursor)), admin), 200);
        String otherOrg = json(createOrg(outsider, "다른 조직"), 201).get("orgId").asString();
        insertOrgMembership(otherOrg, admin, "MEMBER", "ACTIVE", true);
        insertOrgMembership(otherOrg, member, "MEMBER", "ACTIVE", false);
        insertOrgMembership(otherOrg, guest, "MEMBER", "ACTIVE", false);
        String foreign = json(perform(get("/api/orgs/" + otherOrg + "/members?limit=1"), admin), 200)
                .get("nextCursor").asString();

        assertThat(accountIds(first)).containsExactly(admin.toString(), member.toString());
        assertThat(accountIds(second)).containsExactly(guest.toString());
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(perform(get(path("/members?cursor=" + cursor.substring(1))), admin).getStatus()).isEqualTo(400);
        assertThat(perform(get(path("/members?cursor=" + foreign)), admin).getStatus()).isEqualTo(400);
        assertThat(perform(get(path("/members?limit=101")), admin).getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("정지·해제: 204, 반복은 409 ALREADY_PROCESSED, 정지된 사람은 다음 요청부터 그 조직 404, 활동 기록")
    void suspendsAndUnsuspends() throws Exception {
        assertThat(perform(get(path("")), member).getStatus()).isEqualTo(200);

        assertThat(perform(post(path("/members/" + member + "/suspend")), admin).getStatus()).isEqualTo(204);
        MockHttpServletResponse again = perform(post(path("/members/" + member + "/suspend")), admin);

        assertThat(code(again, 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(perform(get(path("")), member).getStatus()).isEqualTo(404);
        assertThat(teamMembershipState(firstTeamId, member)).isEqualTo("ACTIVE");
        assertThat(perform(post(path("/members/" + member + "/unsuspend")), admin).getStatus()).isEqualTo(204);
        assertThat(code(perform(post(path("/members/" + member + "/unsuspend")), admin), 409))
                .isEqualTo("ALREADY_PROCESSED");
        assertThat(perform(get(path("")), member).getStatus()).isEqualTo(200);
        assertThat(log("ORG_MEMBER_SUSPENDED")).containsExactly(admin + " " + member + " ACTIVE SUSPENDED");
        assertThat(log("ORG_MEMBER_UNSUSPENDED")).containsExactly(admin + " " + member + " SUSPENDED ACTIVE");
    }

    @Test
    @DisplayName("정지는 마지막 팀 관리자여도 허용(조직 관리자가 팀을 관리), 내보내기는 409 LAST_ADMIN_REQUIRED")
    void suspendsLastTeamAdminButKeepsTeamAdminOnRemove() throws Exception {
        String team = createTeam("둘째 팀", member);

        assertThat(perform(post(path("/members/" + member + "/suspend")), admin).getStatus()).isEqualTo(204);
        MockHttpServletResponse removed = perform(post(path("/members/" + member + "/remove")), admin);

        assertThat(code(removed, 409)).isEqualTo("LAST_ADMIN_REQUIRED");
        assertThat(orgMembershipState(member)).isEqualTo("SUSPENDED");
        assertThat(teamMembershipState(team, member)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("TEN-INV-06: 다른 팀 관리자가 조직에서 정지됐으면 그 사람은 세지 않아 내보내기 409, 보관한 팀이면 허용")
    void countsOnlyActiveTeamAdmins() throws Exception {
        UUID other = newAccount("other");
        insertOrgMembership(orgId, other, "MEMBER", "SUSPENDED", false);
        String team = createTeam("둘째 팀", member);
        insertTeamMembership(team, other, "TEAM_ADMIN", "ACTIVE");

        assertThat(code(perform(post(path("/members/" + member + "/remove")), admin), 409))
                .isEqualTo("LAST_ADMIN_REQUIRED");

        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :id").param("id", team).update();
        assertThat(perform(post(path("/members/" + member + "/remove")), admin).getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("내보내기: 조직·팀 멤버십 LEFT(사유 REMOVED), 관리자 여부 해제, 팀·조직 활동 기록, 다시 하면 404")
    void removesMember() throws Exception {
        String team = createTeam("둘째 팀", admin);
        insertTeamMembership(team, member, "TEAM_MEMBER", "REQUESTED");
        jdbc.sql("UPDATE org_membership SET org_admin = :admin WHERE account_id = :account")
                .param("admin", true).param("account", member.toString()).update();

        assertThat(perform(post(path("/members/" + member + "/remove")), admin).getStatus()).isEqualTo(204);

        assertThat(jdbc.sql("SELECT CONCAT(state, ':', left_reason) FROM org_membership"
                        + " WHERE org_id = :org AND account_id = :account")
                .param("org", orgId).param("account", member.toString()).query(String.class).single())
                .isEqualTo("LEFT:REMOVED");
        assertThat(orgAdminCount()).isOne();
        assertThat(jdbc.sql("SELECT CONCAT(state, ':', left_reason) FROM team_membership WHERE account_id = :account")
                .param("account", member.toString()).query(String.class).list())
                .containsOnly("LEFT:REMOVED").hasSize(2);
        assertThat(log("ORG_MEMBER_REMOVED")).containsExactly(admin + " " + member + " ACTIVE LEFT");
        assertThat(jdbc.sql("SELECT before_value FROM team_activity_log WHERE action = 'TEAM_MEMBER_REMOVED'"
                        + " ORDER BY before_value")
                .query(String.class).list()).containsExactly("ACTIVE", "REQUESTED");
        assertThat(perform(get(path("")), member).getStatus()).isEqualTo(404);
        assertThat(perform(post(path("/members/" + member + "/remove")), admin).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("자기 자신 정지·내보내기는 400, 혼자인 관리자의 자기 해제는 409, 다른 관리자가 있으면 자기 해제 허용")
    void handlesSelfTargets() throws Exception {
        assertThat(perform(post(path("/members/" + admin + "/suspend")), admin).getStatus()).isEqualTo(400);
        assertThat(perform(post(path("/members/" + admin + "/remove")), admin).getStatus()).isEqualTo(400);
        assertThat(code(perform(post(path("/members/" + admin + "/revoke-org-admin")), admin), 409))
                .isEqualTo("LAST_ADMIN_REQUIRED");
        assertThat(code(perform(post(path("/members/" + admin + "/grant-org-admin")), admin), 409))
                .isEqualTo("ALREADY_PROCESSED");

        assertThat(perform(post(path("/members/" + member + "/grant-org-admin")), admin).getStatus()).isEqualTo(204);
        assertThat(perform(post(path("/members/" + admin + "/revoke-org-admin")), admin).getStatus()).isEqualTo(204);
        assertThat(perform(get(path("/members")), admin).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("SEC-SESS-03: 조직 관리자 지정은 커밋 뒤 대상의 세션을 모두 끊음, 다른 사람 세션은 그대로")
    void grantRevokesTargetSessions() throws Exception {
        insertSession(member);
        insertSession(member);
        insertSession(guest);

        assertThat(perform(post(path("/members/" + member + "/grant-org-admin")), admin).getStatus()).isEqualTo(204);

        await().atMost(Duration.ofSeconds(10)).until(() -> sessionCount(member) == 0);
        assertThat(sessionCount(guest)).isOne();
        assertThat(log("ORG_ADMIN_GRANTED")).containsExactly(admin + " " + member + " - -");
        assertThat(perform(get(path("/members")), member).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("TEN-INV-07: 손님·정지된 사람은 관리자 지정 400, 해제 반복은 409, 다른 조직 사람은 404")
    void validatesAdminTargets() throws Exception {
        UUID suspended = newAccount("suspended");
        insertOrgMembership(orgId, suspended, "MEMBER", "SUSPENDED", false);

        assertThat(perform(post(path("/members/" + guest + "/grant-org-admin")), admin).getStatus()).isEqualTo(400);
        assertThat(perform(post(path("/members/" + suspended + "/grant-org-admin")), admin).getStatus())
                .isEqualTo(400);
        assertThat(code(perform(post(path("/members/" + member + "/revoke-org-admin")), admin), 409))
                .isEqualTo("ALREADY_PROCESSED");
        for (String action : List.of("suspend", "unsuspend", "remove", "grant-org-admin", "revoke-org-admin")) {
            assertThat(perform(post(path("/members/" + outsider + "/" + action)), admin).getStatus())
                    .as(action).isEqualTo(404);
            assertThat(perform(post(path("/members/" + UUID.randomUUID() + "/" + action)), admin).getStatus())
                    .as(action).isEqualTo(404);
        }
        assertThat(orgAdminCount()).isOne();
    }

    @Test
    @DisplayName("SEC-SESS-10: 구성원 변경은 최근 인증이 지나면 401 REAUTH_REQUIRED, 구성원 403, 보관한 조직은 409 READ_ONLY")
    void requiresRecentAuthAndActiveOrg() throws Exception {
        Instant stale = Instant.now().minus(Duration.ofHours(2));
        List<String> actions = List.of("suspend", "unsuspend", "remove", "grant-org-admin", "revoke-org-admin");
        for (String action : actions) {
            MockHttpServletRequestBuilder request = post(path("/members/" + member + "/" + action));
            assertThat(code(perform(request, admin, stale), 401)).as(action).isEqualTo("REAUTH_REQUIRED");
            assertThat(perform(post(path("/members/" + guest + "/" + action)), member).getStatus()).as(action)
                    .isEqualTo(403);
        }
        jdbc.sql("UPDATE organization SET state = 'ARCHIVED' WHERE id = :id").param("id", orgId).update();
        for (String action : actions) {
            assertThat(code(perform(post(path("/members/" + member + "/" + action)), admin), 409)).as(action)
                    .isEqualTo("READ_ONLY");
        }
        assertThat(perform(get(path("/members")), admin).getStatus()).isEqualTo(200);
        assertThat(orgMembershipState(member)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("T-AUTHZ-LASTADMIN: 관리자 둘이 동시에 서로를 해제·정지·내보내도 활동 중인 관리자가 남음")
    void keepsLastAdminUnderConcurrency() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (String action : List.of("revoke-org-admin", "suspend", "remove")) {
                for (int round = 0; round < 3; round++) {
                    resetAdmins();
                    CountDownLatch start = new CountDownLatch(1);
                    CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                            () -> race(start, admin, member, action), executor);
                    CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                            () -> race(start, member, admin, action), executor);
                    start.countDown();
                    List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS),
                            second.get(30, TimeUnit.SECONDS));

                    assertThat(statuses).as(action).containsOnlyOnce(204);
                    assertThat(statuses).as(action).allMatch(status -> List.of(204, 403, 404, 409).contains(status));
                    assertThat(activeAdminCount()).as(action).isOne();
                }
            }
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("T-AUTHZ-LASTADMIN: 같은 팀의 팀 관리자 둘을 동시에 내보내면 하나만 성공하고 팀 관리자가 남음")
    void keepsLastTeamAdminUnderConcurrency() throws Exception {
        UUID other = newAccount("other");
        insertOrgMembership(orgId, other, "MEMBER", "ACTIVE", false);
        String team = createTeam("둘째 팀", member);
        insertTeamMembership(team, other, "TEAM_ADMIN", "ACTIVE");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                    () -> race(start, admin, member, "remove"), executor);
            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                    () -> race(start, admin, other, "remove"), executor);
            start.countDown();
            List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(204, 409);
            assertThat(jdbc.sql("""
                            SELECT COUNT(*) FROM team_membership m
                            JOIN org_membership o ON o.account_id = m.account_id AND o.org_id = :org
                            WHERE m.team_id = :team AND m.role = 'TEAM_ADMIN' AND m.state = 'ACTIVE'
                              AND o.state = 'ACTIVE'
                            """)
                    .param("org", orgId).param("team", team).query(Integer.class).single()).isOne();
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("TEN-MEM-06: 팀 만들기·복원과 첫 팀 관리자 내보내기가 겹쳐도 조직을 떠난 사람이 팀 관리자로 남지 않음")
    void keepsTeamAdminInOrgWhenRacingTeamCreation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                UUID candidate = newAccount("candidate" + round);
                insertOrgMembership(orgId, candidate, "MEMBER", "ACTIVE", false);
                String archived = createTeam("보관 팀 " + round, admin);
                jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :id").param("id", archived).update();
                jdbc.sql("DELETE FROM team_membership WHERE team_id = :id").param("id", archived).update();
                CountDownLatch start = new CountDownLatch(1);
                String name = "경합 팀 " + round;
                CompletableFuture<Integer> created = CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return createTeamResponse(name, candidate).getStatus();
                    }
                    catch (Exception failed) {
                        throw new IllegalStateException(failed);
                    }
                }, executor);
                CompletableFuture<Integer> restored = CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return perform(post(path("/teams/" + archived + "/restore"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"newAdminAccountId\":\"" + candidate + "\"}"), admin).getStatus();
                    }
                    catch (Exception failed) {
                        throw new IllegalStateException(failed);
                    }
                }, executor);
                start.countDown();
                int removed = perform(post(path("/members/" + candidate + "/remove")), admin).getStatus();
                created.get(30, TimeUnit.SECONDS);
                restored.get(30, TimeUnit.SECONDS);

                assertThat(removed).isIn(204, 409);
                assertThat(jdbc.sql("""
                                SELECT COUNT(*) FROM team_membership m
                                JOIN org_membership o ON o.account_id = m.account_id AND o.org_id = :org
                                WHERE m.account_id = :account AND m.state = 'ACTIVE' AND o.state <> 'ACTIVE'
                                """)
                        .param("org", orgId).param("account", candidate.toString()).query(Integer.class).single())
                        .as("round " + round).isZero();
            }
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("TEN-LOG-02: 조직 활동 기록은 조직·모든 팀 기록을 최신부터, 이름 붙임, 거르기·커서")
    void listsOrgActivity() throws Exception {
        String team = createTeam("둘째 팀", member);
        assertThat(perform(post(path("/members/" + guest + "/suspend")), admin).getStatus()).isEqualTo(204);

        JsonNode all = json(perform(get(path("/activity")), admin), 200);
        List<String> actions = values(all, "action");

        assertThat(actions.get(0)).isEqualTo("ORG_MEMBER_SUSPENDED");
        assertThat(actions.subList(1, 3)).containsExactlyInAnyOrder("TEAM_MEMBER_JOINED", "TEAM_CREATED");
        assertThat(actions).contains("ORG_CREATED");
        JsonNode suspended = all.get("items").get(0);
        assertThat(suspended.get("actorName").asString()).isEqualTo("admin");
        assertThat(suspended.get("targetName").asString()).isEqualTo("guest");
        assertThat(suspended.get("teamId").isNull()).isTrue();
        JsonNode joined = item(all, "TEAM_MEMBER_JOINED", team);
        assertThat(joined.get("teamName").asString()).isEqualTo("둘째 팀");
        assertThat(joined.get("targetName").asString()).isEqualTo("member");
        assertThat(item(all, "TEAM_CREATED", null).get("targetName").asString()).isEqualTo("둘째 팀");

        assertThat(values(json(perform(get(path("/activity?teamId=" + team)), admin), 200), "action"))
                .containsExactlyInAnyOrder("TEAM_MEMBER_JOINED", "TEAM_CREATED");
        assertThat(values(json(perform(get(path("/activity?action=ORG_MEMBER_SUSPENDED")), admin), 200), "action"))
                .containsExactly("ORG_MEMBER_SUSPENDED");
        assertThat(json(perform(get(path("/activity?actorId=" + member)), admin), 200).get("items").isEmpty())
                .isTrue();
        assertThat(json(perform(get(path("/activity?from=" + Instant.now().plusSeconds(60))), admin), 200)
                .get("items").isEmpty()).isTrue();
        assertThat(json(perform(get(path("/activity?teamId=" + UUID.randomUUID())), admin), 200)
                .get("items").isEmpty()).isTrue();

        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(perform(get(path("/activity?limit=2" + (cursor == null ? "" : "&cursor=" + cursor))),
                    admin), 200);
            paged.addAll(values(page, "id"));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);
        assertThat(paged).isEqualTo(values(all, "id"));
    }

    @Test
    @DisplayName("활동 기록 검사: 행동 형식·기간 순서 400, 고친 커서 400, 구성원 403, 손님 404, 다른 조직 팀 기록 안 보임")
    void validatesActivityQueries() throws Exception {
        String otherOrg = json(createOrg(outsider, "다른 조직"), 201).get("orgId").asString();
        String otherTeam = jdbc.sql("SELECT id FROM team WHERE org_id = :org").param("org", otherOrg)
                .query(String.class).single();
        Instant now = Instant.now();

        assertThat(fields(perform(get(path("/activity?action=bad-action")), admin)))
                .containsExactly("action:INVALID_FORMAT");
        assertThat(fields(perform(get(path("/activity?from=" + now + "&to=" + now)), admin)))
                .containsExactly("to:NOT_ALLOWED_VALUE");
        assertThat(perform(get(path("/activity?cursor=abc.def")), admin).getStatus()).isEqualTo(400);
        assertThat(perform(get(path("/activity?from=yesterday")), admin).getStatus()).isEqualTo(400);
        assertThat(perform(get(path("/activity")), member).getStatus()).isEqualTo(403);
        assertThat(perform(get(path("/activity")), guest).getStatus()).isEqualTo(404);
        assertThat(json(perform(get(path("/activity?teamId=" + otherTeam)), admin), 200).get("items").isEmpty())
                .isTrue();
        assertThat(values(json(perform(get(path("/activity?limit=100")), admin), 200), "teamId"))
                .doesNotContain(otherTeam);
    }

    private int race(CountDownLatch start, UUID actor, UUID target, String action) {
        try {
            start.await();
            return perform(post(path("/members/" + target + "/" + action)), actor).getStatus();
        }
        catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private void resetAdmins() {
        jdbc.sql("""
                        UPDATE org_membership SET org_admin = :admin, state = 'ACTIVE', left_reason = NULL
                        WHERE org_id = :org AND account_id IN (:accounts)
                        """)
                .param("admin", true).param("org", orgId)
                .param("accounts", List.of(admin.toString(), member.toString())).update();
        jdbc.sql("UPDATE team_membership SET state = 'ACTIVE', left_reason = NULL WHERE team_id = :team")
                .param("team", firstTeamId).update();
    }

    private String path(String suffix) {
        return "/api/orgs/" + orgId + suffix;
    }

    private String createTeam(String name, UUID firstAdmin) throws Exception {
        return json(createTeamResponse(name, firstAdmin), 201).get("teamId").asString();
    }

    private MockHttpServletResponse createTeamResponse(String name, UUID firstAdmin) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", orgId).query(String.class).single());
        body.put("firstAdminAccountId", firstAdmin.toString());
        return perform(post(path("/teams")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), admin);
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

    private void insertOrgMembership(String org, UUID account, String kind, String state, boolean orgAdmin) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version)
                        VALUES (:id, :org, :account, :kind, :admin, :state, 'INVITATION', :expires, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", org)
                .param("account", account.toString())
                .param("kind", kind)
                .param("admin", orgAdmin)
                .param("state", state)
                .param("expires", "GUEST".equals(kind) ? now.plusDays(30) : null)
                .param("now", now)
                .update();
    }

    private void insertTeamMembership(String teamId, UUID account, String role, String state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, created_at,
                            updated_at, version)
                        VALUES (:id, :team, :account, :role, :state, 'DIRECT', :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("team", teamId)
                .param("account", account.toString())
                .param("role", role)
                .param("state", state)
                .param("now", now)
                .update();
    }

    private void insertSession(UUID account) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO auth_session (session_id_hash, account_id, authenticated_at, created_at,
                            last_used_at, expires_at)
                        VALUES (:hash, :account, :now, :now, :now, :expires)
                        """)
                .param("hash", (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""))
                .param("account", account.toString())
                .param("now", now)
                .param("expires", now.plusHours(1))
                .update();
    }

    private int sessionCount(UUID account) {
        return jdbc.sql("SELECT COUNT(*) FROM auth_session WHERE account_id = :account")
                .param("account", account.toString()).query(Integer.class).single();
    }

    private String orgMembershipState(UUID account) {
        return jdbc.sql("SELECT state FROM org_membership WHERE org_id = :org AND account_id = :account")
                .param("org", orgId).param("account", account.toString()).query(String.class).single();
    }

    private String teamMembershipState(String teamId, UUID account) {
        return jdbc.sql("SELECT state FROM team_membership WHERE team_id = :team AND account_id = :account")
                .param("team", teamId).param("account", account.toString()).query(String.class).single();
    }

    private int orgAdminCount() {
        return jdbc.sql("SELECT COUNT(*) FROM org_membership WHERE org_id = :org AND org_admin = :admin")
                .param("org", orgId).param("admin", true).query(Integer.class).single();
    }

    private int activeAdminCount() {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM org_membership
                        WHERE org_id = :org AND org_admin = :admin AND state = 'ACTIVE'
                        """)
                .param("org", orgId).param("admin", true).query(Integer.class).single();
    }

    private List<String> log(String action) {
        return jdbc.sql("""
                        SELECT actor_id, target_id, before_value, after_value FROM org_activity_log
                        WHERE org_id = :org AND action = :action ORDER BY occurred_at
                        """)
                .param("org", orgId).param("action", action)
                .query((rs, rowNum) -> rs.getString("actor_id") + " " + rs.getString("target_id") + " "
                        + valueOrDash(rs.getString("before_value")) + " " + valueOrDash(rs.getString("after_value")))
                .list();
    }

    private static String valueOrDash(String value) {
        return value == null ? "-" : value;
    }

    private static JsonNode item(JsonNode page, String action, String teamId) {
        for (JsonNode item : page.get("items")) {
            boolean sameTeam = teamId == null ? item.get("teamId").isNull()
                    : !item.get("teamId").isNull() && item.get("teamId").asString().equals(teamId);
            if (item.get("action").asString().equals(action) && sameTeam
                    && (teamId != null || "TEAM".equals(item.get("targetType").asString()))) {
                return item;
            }
        }
        throw new AssertionError("활동 기록 없음: " + action);
    }

    private static List<String> accountIds(JsonNode page) {
        return values(page, "accountId");
    }

    private static List<String> values(JsonNode page, String field) {
        List<String> values = new ArrayList<>();
        page.get("items").forEach(item -> values.add(item.get(field).isNull() ? null : item.get(field).asString()));
        return values;
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
