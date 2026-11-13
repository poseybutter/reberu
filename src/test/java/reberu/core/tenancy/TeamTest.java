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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TeamTest {

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
        joinOrg(orgId, member, "MEMBER");
        joinOrg(orgId, guest, "GUEST");
    }

    @Test
    @DisplayName("팀 만들기: 직무 유형 기본값 복사, 첫 팀 관리자, 활동 기록")
    void createsTeam() throws Exception {
        MockHttpServletResponse response = createTeam(admin, orgId, team("디자인 팀", "PUBLIC", jobType("디자인"),
                member));

        assertThat(response.getStatus()).isEqualTo(201);
        String teamId = json(response).get("teamId").asString();
        Map<String, Object> team = jdbc.sql("SELECT * FROM team WHERE id = :id").param("id", teamId)
                .query().singleRow();
        assertThat(team).containsEntry("org_id", orgId).containsEntry("name", "디자인 팀")
                .containsEntry("name_key", "디자인 팀").containsEntry("visibility", "PUBLIC")
                .containsEntry("state", "ACTIVE").containsEntry("report_notice_titles", "[\"검토 요청\"]");
        Map<String, Object> membership = jdbc.sql("SELECT * FROM team_membership WHERE team_id = :team")
                .param("team", teamId).query().singleRow();
        assertThat(membership).containsEntry("account_id", member.toString()).containsEntry("role", "TEAM_ADMIN")
                .containsEntry("joined_via", "DIRECT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM org_activity_log WHERE action = 'TEAM_CREATED' AND target_id = :t")
                .param("t", teamId).query(Integer.class).single()).isOne();
    }

    @Test
    @DisplayName("대소문자·공백·전각만 다른 팀 이름 거부(DUPLICATE)")
    void rejectsLookalikeTeamNames() throws Exception {
        assertThat(createTeam(admin, orgId, team("Alpha  Team", "PRIVATE", jobType("기본"), admin)).getStatus())
                .isEqualTo(201);

        assertThat(fields(createTeam(admin, orgId, team(" alpha team ", "PRIVATE", jobType("기본"), admin))))
                .containsExactly("name:DUPLICATE");
        assertThat(fields(createTeam(admin, orgId, team("ＡＬＰＨＡ ＴＥＡＭ", "PRIVATE", jobType("기본"), admin))))
                .containsExactly("name:DUPLICATE");
        assertThat(fields(createTeam(admin, orgId, team("첫  팀", "PRIVATE", jobType("기본"), admin))))
                .containsExactly("name:DUPLICATE");
        assertThat(count("team")).isEqualTo(2);
    }

    @Test
    @DisplayName("TEN-TEAM-06: 커밋 전인 같은 이름과 겹치는 팀 만들기는 유일 제약으로 막고 DUPLICATE")
    void createRaceHitsUniqueConstraint() throws Exception {
        UUID basic = jobType("기본");
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            jdbc.sql("""
                            INSERT INTO team (id, org_id, name, name_key, visibility, join_mode, state, job_type_id,
                                report_week_start, report_notice_titles, created_at, updated_at, version)
                            VALUES (:id, :org, '경합', '경합', 'PRIVATE', 'APPROVAL', 'ACTIVE', NULL, 1, '[]',
                                :now, :now, 0)
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("org", orgId)
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

        CompletableFuture<MockHttpServletResponse> create = CompletableFuture.supplyAsync(() -> {
            try {
                return createTeam(admin, orgId, team("경합", "PRIVATE", basic, admin));
            }
            catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        Thread.sleep(500);
        release.countDown();
        holder.join();

        assertThat(fields(create.get(30, TimeUnit.SECONDS))).containsExactly("name:DUPLICATE");
        assertThat(count("team")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM org_activity_log WHERE action = 'TEAM_CREATED'")
                .query(Integer.class).single()).isOne();
    }

    @Test
    @DisplayName("SEC-AUTHZ-05·08·09·TEN-CTX: 구성원은 403, 손님·비멤버·다른 조직 관리자는 팀 만들기 404")
    void onlyOrgAdminCreatesTeam() throws Exception {
        String otherOrg = json(createOrg(outsider, "다른 조직")).get("orgId").asString();

        MockHttpServletResponse byMember = createTeam(member, orgId, team("몰래 팀", "PRIVATE", jobType("기본"), member));
        assertThat(byMember.getStatus()).isEqualTo(403);
        assertThat(code(byMember)).isEqualTo("FORBIDDEN_ACTION");
        for (UUID actor : List.of(guest, outsider)) {
            MockHttpServletResponse response = createTeam(actor, orgId, team("몰래 팀", "PRIVATE", jobType("기본"),
                    actor));
            assertThat(response.getStatus()).as(actor.toString()).isEqualTo(404);
            assertThat(code(response)).isEqualTo("NOT_FOUND");
        }
        assertThat(createTeam(admin, otherOrg, team("남의 팀", "PRIVATE", jobType("기본"), admin)).getStatus())
                .isEqualTo(404);
        assertThat(count("team")).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 조직 직무 유형·조직 밖 계정·손님은 팀 만들기에 쓸 수 없음")
    void rejectsForeignReferences() throws Exception {
        String otherOrg = json(createOrg(outsider, "다른 조직")).get("orgId").asString();
        String foreignJobType = jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", otherOrg).query(String.class).single();

        assertThat(fields(createTeam(admin, orgId, team("팀 A", "PRIVATE", UUID.fromString(foreignJobType), admin))))
                .containsExactly("jobTypeId:NOT_ALLOWED_VALUE");
        assertThat(fields(createTeam(admin, orgId, team("팀 B", "PRIVATE", jobType("기본"), outsider))))
                .containsExactly("firstAdminAccountId:NOT_ALLOWED_VALUE");
        assertThat(fields(createTeam(admin, orgId, team("팀 C", "PRIVATE", jobType("기본"), guest))))
                .containsExactly("firstAdminAccountId:NOT_ALLOWED_VALUE");
        Map<String, Object> body = team("팀 D", "PRIVATE", jobType("기본"), admin);
        body.put("role", "TEAM_ADMIN");
        assertThat(createTeam(admin, orgId, body).getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("보관된 조직에는 팀을 만들 수 없음(409 READ_ONLY)")
    void archivedOrganizationIsReadOnly() throws Exception {
        jdbc.sql("UPDATE organization SET state = 'ARCHIVED' WHERE id = :id").param("id", orgId).update();

        MockHttpServletResponse response = createTeam(admin, orgId, team("늦은 팀", "PRIVATE", jobType("기본"), admin));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("READ_ONLY");
    }

    @Test
    @DisplayName("팀 보관·복원, 같은 요청 반복은 409 ALREADY_PROCESSED, 관리자 있으면 새 관리자 지정 거부")
    void archivesAndRestores() throws Exception {
        assertThat(perform(post(teamPath(firstTeamId) + "/archive"), admin).getStatus()).isEqualTo(204);
        assertThat(state(firstTeamId)).isEqualTo("ARCHIVED");
        assertThat(code(perform(post(teamPath(firstTeamId) + "/archive"), admin))).isEqualTo("ALREADY_PROCESSED");

        assertThat(fields(perform(post(teamPath(firstTeamId) + "/restore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newAdminAccountId\":\"" + member + "\"}"), admin)))
                .containsExactly("newAdminAccountId:NOT_ALLOWED_VALUE");
        assertThat(perform(post(teamPath(firstTeamId) + "/restore"), admin).getStatus()).isEqualTo(204);
        assertThat(state(firstTeamId)).isEqualTo("ACTIVE");
        assertThat(code(perform(post(teamPath(firstTeamId) + "/restore"), admin))).isEqualTo("ALREADY_PROCESSED");
        assertThat(jdbc.sql("SELECT action FROM org_activity_log WHERE target_id = :t ORDER BY occurred_at")
                .param("t", firstTeamId).query(String.class).list())
                .containsSubsequence("TEAM_CREATED", "TEAM_ARCHIVED", "TEAM_RESTORED");
    }

    @Test
    @DisplayName("TEN-INV-06: 팀 관리자 없이 복원 불가, 새 관리자는 조직의 ACTIVE 구성원만")
    void restoreRequiresTeamAdmin() throws Exception {
        perform(post(teamPath(firstTeamId) + "/archive"), admin);
        jdbc.sql("UPDATE team_membership SET state = 'LEFT' WHERE team_id = :t").param("t", firstTeamId).update();

        assertThat(fields(perform(post(teamPath(firstTeamId) + "/restore"), admin)))
                .containsExactly("newAdminAccountId:REQUIRED");
        assertThat(fields(restoreWith(guest))).containsExactly("newAdminAccountId:NOT_ALLOWED_VALUE");
        assertThat(fields(restoreWith(outsider))).containsExactly("newAdminAccountId:NOT_ALLOWED_VALUE");
        assertThat(fields(restoreWith(admin))).containsExactly("newAdminAccountId:NOT_ALLOWED_VALUE");
        assertThat(state(firstTeamId)).isEqualTo("ARCHIVED");

        assertThat(restoreWith(member).getStatus()).isEqualTo(204);
        assertThat(state(firstTeamId)).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT role FROM team_membership WHERE team_id = :t AND account_id = :a AND state = 'ACTIVE'")
                .param("t", firstTeamId).param("a", member.toString()).query(String.class).single())
                .isEqualTo("TEAM_ADMIN");
    }

    @Test
    @DisplayName("보관·복원은 최근 인증 필요(401 REAUTH_REQUIRED), 구성원은 403, 없는 팀은 404")
    void archiveRequiresRecentAuthAndAdmin() throws Exception {
        MockHttpServletResponse stale = mockMvc.perform(post(teamPath(firstTeamId) + "/archive")
                .with(authentication(new AccountAuthentication(admin, Instant.now().minus(Duration.ofHours(2)))))
                .with(csrfToken())).andReturn().getResponse();

        assertThat(stale.getStatus()).isEqualTo(401);
        assertThat(code(stale)).isEqualTo("REAUTH_REQUIRED");
        assertThat(perform(post(teamPath(firstTeamId) + "/archive"), member).getStatus()).isEqualTo(403);
        assertThat(perform(post("/api/orgs/" + orgId + "/teams/" + UUID.randomUUID() + "/archive"), admin)
                .getStatus()).isEqualTo(404);
        assertThat(state(firstTeamId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("팀 목록: 조직 관리자는 모든 팀, 구성원은 공개 팀과 내 팀, 손님·비멤버는 404")
    void listsTeamsByRole() throws Exception {
        String publicTeam = json(createTeam(admin, orgId, team("공개 팀", "PUBLIC", jobType("기본"), admin)))
                .get("teamId").asString();
        String privateTeam = json(createTeam(admin, orgId, team("비공개 팀", "PRIVATE", jobType("기본"), admin)))
                .get("teamId").asString();
        String mine = json(createTeam(admin, orgId, team("내 팀", "PRIVATE", jobType("기본"), member)))
                .get("teamId").asString();

        assertThat(teamIds(list(admin, ""))).containsExactly(firstTeamId, publicTeam, privateTeam, mine);
        assertThat(teamIds(list(member, ""))).containsExactly(publicTeam, mine);
        assertThat(list(guest, "").getStatus()).isEqualTo(404);
        assertThat(list(outsider, "").getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("API-PAGE-01: 팀 목록 커서, 고친 커서·다른 조직 커서는 400")
    void pagesTeamsWithSignedCursor() throws Exception {
        for (int i = 1; i <= 3; i++) {
            createTeam(admin, orgId, team("팀 " + i, "PRIVATE", jobType("기본"), admin));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(list(admin, "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor)));
            page.get("items").forEach(item -> seen.add(item.get("id").asString()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).hasSize(4).doesNotHaveDuplicates();

        String firstCursor = json(list(admin, "?limit=1")).get("nextCursor").asString();
        String tampered = firstCursor.substring(0, firstCursor.length() - 2) + "AA";
        assertThat(list(admin, "?limit=1&cursor=" + tampered).getStatus()).isEqualTo(400);
        String otherOrg = json(createOrg(outsider, "다른 조직")).get("orgId").asString();
        MockHttpServletResponse foreign = perform(get("/api/orgs/" + otherOrg + "/teams?cursor=" + firstCursor),
                outsider);
        assertThat(foreign.getStatus()).isEqualTo(400);
        assertThat(list(admin, "?limit=0").getStatus()).isEqualTo(400);
        assertThat(list(admin, "?limit=101").getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("첫 화면: 팀 하나 TEAM_HOME, 둘 이상 TEAM_PICKER, 팀 없는 구성원 TEAM_PICKER, 조직 없음 START·JOIN_PENDING")
    void decidesHomeScreen() throws Exception {
        assertThat(home(admin).get("next").get("screen").asString()).isEqualTo("TEAM_HOME");
        assertThat(home(admin).get("next").get("teamId").asString()).isEqualTo(firstTeamId);
        assertThat(session(admin).get("next").get("screen").asString()).isEqualTo("TEAM_HOME");
        assertThat(session(admin).get("deploymentMode").asString()).isEqualTo("MULTI_ORG");

        assertThat(home(member).get("next").get("screen").asString()).isEqualTo("TEAM_PICKER");
        assertThat(home(outsider).get("next").get("screen").asString()).isEqualTo("START");

        createTeam(admin, orgId, team("둘째 팀", "PRIVATE", jobType("기본"), admin));
        assertThat(home(admin).get("next").get("screen").asString()).isEqualTo("TEAM_PICKER");
        assertThat(session(admin).get("next").get("screen").asString()).isEqualTo("TEAM_PICKER");

        UUID requester = newAccount("requester");
        insertOrgMembership(orgId, requester, "MEMBER", "REQUESTED");
        assertThat(home(requester).get("next").get("screen").asString()).isEqualTo("JOIN_PENDING");

        for (UUID actor : List.of(admin, member, guest, outsider, requester)) {
            assertThat(session(actor).get("next")).as(actor.toString()).isEqualTo(home(actor).get("next"));
        }
    }

    @Test
    @DisplayName("TEN-HOME-04: 처리될 수 없는 가입 요청(정지·보관 조직, 보관 팀)은 JOIN_PENDING으로 세지 않음")
    void ignoresUnprocessableRequests() throws Exception {
        UUID requester = newAccount("requester");
        insertOrgMembership(orgId, requester, "MEMBER", "REQUESTED");

        for (String state : List.of("SUSPENDED", "ARCHIVED")) {
            jdbc.sql("UPDATE organization SET state = :state WHERE id = :id").param("state", state)
                    .param("id", orgId).update();
            assertThat(home(requester).get("next").get("screen").asString()).as(state).isEqualTo("START");
            assertThat(session(requester).get("next").get("screen").asString()).as(state).isEqualTo("START");
        }
        jdbc.sql("UPDATE organization SET state = 'ACTIVE' WHERE id = :id").param("id", orgId).update();
        assertThat(home(requester).get("next").get("screen").asString()).isEqualTo("JOIN_PENDING");

        UUID teamRequester = newAccount("team-requester");
        insertTeamMembership(firstTeamId, teamRequester, "REQUESTED");
        jdbc.sql("UPDATE team SET state = 'ARCHIVED' WHERE id = :id").param("id", firstTeamId).update();
        assertThat(session(teamRequester).get("next").get("screen").asString()).isEqualTo("START");
        jdbc.sql("UPDATE team SET state = 'ACTIVE' WHERE id = :id").param("id", firstTeamId).update();
        assertThat(session(teamRequester).get("next").get("screen").asString()).isEqualTo("JOIN_PENDING");
    }

    @Test
    @DisplayName("SEC-AUTHZ-09·TEN-ISO-02: 다른 조직 관리자는 이 조직 팀을 보관·복원할 수 없음(404, 상태 그대로)")
    void otherOrgAdminCannotArchiveOrRestore() throws Exception {
        String otherOrg = json(createOrg(outsider, "다른 조직")).get("orgId").asString();

        for (String action : List.of("archive", "restore")) {
            assertThat(perform(post("/api/orgs/" + otherOrg + "/teams/" + firstTeamId + "/" + action), outsider)
                    .getStatus()).as("다른 조직 경로 " + action).isEqualTo(404);
            assertThat(perform(post(teamPath(firstTeamId) + "/" + action), outsider).getStatus())
                    .as("이 조직 경로 " + action).isEqualTo(404);
        }
        assertThat(state(firstTeamId)).isEqualTo("ACTIVE");

        perform(post(teamPath(firstTeamId) + "/archive"), admin);
        assertThat(perform(post(teamPath(firstTeamId) + "/restore"), member).getStatus()).isEqualTo(403);
        for (UUID actor : List.of(guest, outsider)) {
            assertThat(perform(post(teamPath(firstTeamId) + "/restore"), actor).getStatus())
                    .as("복원 " + actor).isEqualTo(404);
        }
        assertThat(perform(post("/api/orgs/" + otherOrg + "/teams/" + firstTeamId + "/restore"), outsider)
                .getStatus()).isEqualTo(404);
        assertThat(state(firstTeamId)).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("팀 목록은 같은 시각에 만든 팀도 만든 시각·ID 순서로 커서와 함께 빠짐없이 나옴")
    void ordersTeamsByCreationTimeThenId() throws Exception {
        LocalDateTime same = LocalDateTime.of(2026, 11, 9, 9, 0);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(json(createTeam(admin, orgId, team("같은 시각 " + i, "PRIVATE", jobType("기본"), admin)))
                    .get("teamId").asString());
        }
        jdbc.sql("UPDATE team SET created_at = :at WHERE org_id = :org AND id <> :first")
                .param("at", same).param("org", orgId).param("first", firstTeamId).update();
        jdbc.sql("UPDATE team SET created_at = :at WHERE id = :first")
                .param("at", same.plusDays(1)).param("first", firstTeamId).update();
        ids.sort(String::compareTo);
        List<String> expected = new ArrayList<>(ids);
        expected.add(firstTeamId);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(list(admin, "?limit=1" + (cursor == null ? "" : "&cursor=" + cursor)));
            page.get("items").forEach(item -> seen.add(item.get("id").asString()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("첫 화면: 보관된 팀은 목록에 보이지만 바로 이동 판정에서 빠짐")
    void archivedTeamIsListedButNotCounted() throws Exception {
        String second = json(createTeam(admin, orgId, team("둘째 팀", "PRIVATE", jobType("개발"), admin)))
                .get("teamId").asString();
        perform(post(teamPath(firstTeamId) + "/archive"), admin);

        JsonNode home = home(admin);

        assertThat(home.get("next").get("screen").asString()).isEqualTo("TEAM_HOME");
        assertThat(home.get("next").get("teamId").asString()).isEqualTo(second);
        JsonNode org = home.get("orgs").get(0);
        assertThat(org.get("name").asString()).isEqualTo("새벽 서재");
        assertThat(org.get("orgAdmin").asBoolean()).isTrue();
        List<String> states = new ArrayList<>();
        org.get("teams").forEach(team -> states.add(team.get("name").asString() + ":" + team.get("state").asString()
                + ":" + team.get("jobTypeName").asString()));
        assertThat(states).containsExactly("첫 팀:ARCHIVED:기본", "둘째 팀:ACTIVE:개발");
        assertThat(home.get("invitationCount").asInt()).isZero();
    }

    @Test
    @DisplayName("API-TEST-06: 같은 키로 팀 만들기 두 번이면 팀 하나")
    void sameKeyCreatesTeamOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = team("한 번 팀", "PRIVATE", jobType("기본"), admin);

        MockHttpServletResponse first = createTeam(admin, orgId, body, key);
        MockHttpServletResponse second = createTeam(admin, orgId, body, key);

        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(count("team")).isEqualTo(2);
    }

    private MockHttpServletResponse restoreWith(UUID newAdmin) throws Exception {
        return perform(post(teamPath(firstTeamId) + "/restore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newAdminAccountId\":\"" + newAdmin + "\"}"), admin);
    }

    private String teamPath(String teamId) {
        return "/api/orgs/" + orgId + "/teams/" + teamId;
    }

    private MockHttpServletResponse list(UUID actor, String query) throws Exception {
        return perform(get("/api/orgs/" + orgId + "/teams" + query), actor);
    }

    private List<String> teamIds(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        json(response).get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private JsonNode home(UUID actor) throws Exception {
        MockHttpServletResponse response = perform(get("/api/me/home"), actor);
        assertThat(response.getStatus()).isEqualTo(200);
        return json(response);
    }

    private JsonNode session(UUID actor) throws Exception {
        return json(perform(get("/api/auth/session"), actor));
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

    private MockHttpServletResponse createTeam(UUID actor, String org, Map<String, Object> body) throws Exception {
        return createTeam(actor, org, body, UUID.randomUUID().toString());
    }

    private MockHttpServletResponse createTeam(UUID actor, String org, Map<String, Object> body, String key)
            throws Exception {
        return perform(post("/api/orgs/" + org + "/teams").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), actor);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, UUID actor) throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(actor, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

    private static Map<String, Object> team(String name, String visibility, UUID jobTypeId, UUID firstAdmin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", visibility);
        body.put("jobTypeId", jobTypeId.toString());
        body.put("firstAdminAccountId", firstAdmin.toString());
        return body;
    }

    private UUID jobType(String name) {
        return UUID.fromString(jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = :name")
                .param("org", orgId).param("name", name).query(String.class).single());
    }

    private void joinOrg(String org, UUID account, String kind) {
        insertOrgMembership(org, account, kind, "ACTIVE");
    }

    private void insertOrgMembership(String org, UUID account, String kind, String state) {
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

    private String state(String teamId) {
        return jdbc.sql("SELECT state FROM team WHERE id = :id").param("id", teamId).query(String.class).single();
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

    private UUID newAccount(String name) {
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(),
                name + "@example.com", name, null, null), "v1", "v1", Instant.now());
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

}
