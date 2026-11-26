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
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.account.AccountState;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import reberu.core.tenancy.service.WithdrawalService;
import reberu.core.tenancy.service.WithdrawalService.OrgName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawalTest {

    private static final String CONFIRM = "탈퇴합니다";
    private static final String ORG_NAME = "새벽 서재";
    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "invitation",
            "invite_link", "team_membership", "team", "job_type", "org_membership", "organization",
            "api_idempotency_key", "auth_session", "account_consent", "account");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    WithdrawalService withdrawals;

    private UUID admin;
    private UUID member;
    private String orgId;
    private String firstTeamId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = newAccount("admin", "admin@example.com");
        member = newAccount("member", "member@example.com");
        JsonNode created = json(createOrg(admin), 201);
        orgId = created.get("orgId").asString();
        firstTeamId = created.get("teamId").asString();
        insertOrgMembership(member, "MEMBER", "ACTIVE", false);
        insertTeamMembership(firstTeamId, member, "TEAM_MEMBER", "ACTIVE");
    }

    @Test
    @DisplayName("T-PRIV-DELETE: 탈퇴하면 개인정보를 지우고 sub를 무작위 값으로 바꾸고, 모든 멤버십 LEFT(사유 ACCOUNT_DELETED), 기록은 남김")
    void deletesPersonalData() throws Exception {
        String suspendedTeam = createTeam("둘째 팀");
        insertTeamMembership(suspendedTeam, member, "TEAM_MEMBER", "SUSPENDED");
        String requestedTeam = createTeam("셋째 팀");
        insertTeamMembership(requestedTeam, member, "TEAM_MEMBER", "REQUESTED");
        String accepted = insertInvitation(null, "member@example.com", "ACCEPTED", member);
        String pending = insertInvitation(firstTeamId, "member@example.com", "PENDING", null);
        String other = insertInvitation(null, "other@example.com", "PENDING", null);
        insertSession(member);
        String sub = jdbc.sql("SELECT google_sub FROM account WHERE id = :id").param("id", member.toString())
                .query(String.class).single();

        assertThat(withdraw(member, CONFIRM, List.of()).getStatus()).isEqualTo(204);

        Map<String, Object> account = jdbc.sql("SELECT * FROM account WHERE id = :id")
                .param("id", member.toString()).query().singleRow();
        assertThat(account).containsEntry("state", "DELETED").containsEntry("email", null)
                .containsEntry("display_name", null).containsEntry("picture_url", null)
                .containsEntry("workspace_domain", null);
        assertThat(account.get("deleted_at")).isNotNull();
        assertThat((String) account.get("google_sub")).matches("deleted:[0-9a-f]{64}");
        assertThat(accounts.findByGoogleSub(sub)).isEmpty();
        assertThat(orgMembership(member)).isEqualTo("LEFT:ACCOUNT_DELETED:false");
        assertThat(jdbc.sql("SELECT CONCAT(state, ':', left_reason) FROM team_membership WHERE account_id = :account")
                .param("account", member.toString()).query(String.class).list())
                .containsOnly("LEFT:ACCOUNT_DELETED").hasSize(3);
        assertThat(orgLog("ORG_MEMBER_WITHDRAWN")).containsExactly(member + " " + member + " ACTIVE LEFT");
        assertThat(teamLog("TEAM_MEMBER_WITHDRAWN")).containsExactlyInAnyOrder(
                firstTeamId + " " + member + " ACTIVE LEFT", suspendedTeam + " " + member + " SUSPENDED LEFT",
                requestedTeam + " " + member + " REQUESTED LEFT");
        assertThat(invitation(accepted)).isEqualTo("ACCEPTED:-");
        assertThat(invitation(pending)).isEqualTo("REVOKED:-");
        assertThat(invitation(other)).isEqualTo("PENDING:other@example.com");
        assertThat(orgMembership(admin)).isEqualTo("ACTIVE:-:true");
        JsonNode activity = json(perform(get("/api/orgs/" + orgId + "/activity")
                .param("actorId", member.toString()).param("action", "ORG_MEMBER_WITHDRAWN"), admin), 200);
        assertThat(activity.get("items")).hasSize(1);
        assertThat(activity.get("items").get(0).get("actorName").isNull()).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> sessionCount(member) == 0);
    }

    @Test
    @DisplayName("TEN-WD-07: 같은 이메일의 다른 계정이 있으면 그 이메일로 온 대기 초대는 그대로 둠")
    void keepsInvitationsOfSharedEmail() throws Exception {
        newAccount("twin", "member@example.com");
        String pending = insertInvitation(null, "member@example.com", "PENDING", null);
        String accepted = insertInvitation(firstTeamId, "member@example.com", "ACCEPTED", member);

        assertThat(withdraw(member, CONFIRM, List.of()).getStatus()).isEqualTo(204);

        assertThat(invitation(pending)).isEqualTo("PENDING:member@example.com");
        assertThat(invitation(accepted)).isEqualTo("ACCEPTED:-");
    }

    @Test
    @DisplayName("TEN-WD-07: 같은 이메일의 두 계정이 동시에 탈퇴하면 둘째는 첫째를 기다린 뒤 그 이메일로 온 대기 초대를 지움")
    void forgetsSharedEmailUnderConcurrency() throws Exception {
        UUID twin = newAccount("twin", "member@example.com");
        String pending = insertInvitation(null, "member@example.com", "PENDING", null);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactions.executeWithoutResult(status -> {
            withdrawals.withdraw(member, CONFIRM, List.of());
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<MockHttpServletResponse> second = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try {
                return withdraw(twin, CONFIRM, List.of());
            }
            catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500);
        assertThat(second.isDone()).as("잠금 대기").isFalse();
        release.countDown();
        holder.join();

        assertThat(second.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(204);
        assertThat(accountState(member)).isEqualTo("DELETED");
        assertThat(accountState(twin)).isEqualTo("DELETED");
        assertThat(invitation(pending)).isEqualTo("REVOKED:-");
    }

    @Test
    @DisplayName("SEC-PRIV-04: 로그인이 계정을 읽은 뒤 탈퇴가 끝나도 로그인 기록이 탈퇴 결과를 덮어쓰지 않음")
    void loginDoesNotOverwriteWithdrawal() {
        String sub = jdbc.sql("SELECT google_sub FROM account WHERE id = :id").param("id", member.toString())
                .query(String.class).single();

        AccountState locked = transactions.execute(status -> {
            UUID found = accounts.findByGoogleSub(sub).orElseThrow().id();
            CompletableFuture.runAsync(() -> {
                try {
                    assertThat(withdraw(member, CONFIRM, List.of()).getStatus()).isEqualTo(204);
                }
                catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
            }).join();
            return accounts.recordLogin(found, new GoogleProfile(sub, "member@example.com", "member", null, null),
                    Instant.now());
        });

        assertThat(locked).isEqualTo(AccountState.DELETED);
        Map<String, Object> account = jdbc.sql("SELECT * FROM account WHERE id = :id")
                .param("id", member.toString()).query().singleRow();
        assertThat(account).containsEntry("state", "DELETED").containsEntry("email", null)
                .containsEntry("display_name", null);
        assertThat((String) account.get("google_sub")).matches("deleted:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("SEC-INPUT-01:확인 문구 20자, 조직 이름 확인 40자를 넘으면 잠그기 전에 400 TOO_LONG")
    void rejectsTooLongConfirmations() throws Exception {
        removeMember();

        assertThat(fields(withdraw(admin, "가".repeat(21), confirmed())))
                .containsExactly("confirmText:TOO_LONG");
        assertThat(fields(withdraw(admin, CONFIRM, List.of(new OrgName(UUID.fromString(orgId), "가".repeat(41))))))
                .containsExactly("orgNameConfirmations[0].name:TOO_LONG");
        assertThat(accountState(admin)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("SEC-PRIV-03: 다른 활동 멤버가 있는 곳의 유일한 관리자는 409 LAST_ADMIN_REQUIRED, 정지된 사람은 세지 않고 손님은 셈")
    void keepsLastAdmins() throws Exception {
        JsonNode check = check(admin);
        assertThat(check.get("withdrawable").asBoolean()).isFalse();
        assertThat(check.get("confirmText").asString()).isEqualTo(CONFIRM);
        assertThat(ids(check, "transferOrgs", "orgId")).containsExactly(orgId);
        assertThat(check.get("transferOrgs").get(0).get("name").asString()).isEqualTo(ORG_NAME);
        assertThat(ids(check, "transferTeams", "teamId")).containsExactly(firstTeamId);
        assertThat(code(withdraw(admin, CONFIRM, List.of()), 409)).isEqualTo("LAST_ADMIN_REQUIRED");
        assertThat(orgMembership(admin)).isEqualTo("ACTIVE:-:true");
        assertThat(accountState(admin)).isEqualTo("ACTIVE");

        setOrgState(member, "SUSPENDED");
        check = check(admin);
        assertThat(check.get("withdrawable").asBoolean()).isTrue();
        assertThat(ids(check, "archiveOrgs", "orgId")).containsExactly(orgId);

        UUID guest = newAccount("guest", "guest@example.com");
        insertOrgMembership(guest, "GUEST", "ACTIVE", false);
        assertThat(ids(check(admin), "transferOrgs", "orgId")).containsExactly(orgId);

        setOrgState(member, "ACTIVE");
        jdbc.sql("UPDATE org_membership SET org_admin = :admin WHERE account_id = :account")
                .param("admin", true).param("account", member.toString()).update();
        check = check(admin);
        assertThat(ids(check, "transferOrgs", "orgId")).isEmpty();
        assertThat(ids(check, "transferTeams", "teamId")).containsExactly(firstTeamId);
        assertThat(code(withdraw(admin, CONFIRM, List.of()), 409)).isEqualTo("LAST_ADMIN_REQUIRED");

        setTeamRole(member, "TEAM_ADMIN");
        check = check(admin);
        assertThat(check.get("withdrawable").asBoolean()).isTrue();
        assertThat(ids(check, "archiveOrgs", "orgId")).isEmpty();
        assertThat(ids(check, "archiveTeams", "teamId")).isEmpty();
        assertThat(withdraw(admin, CONFIRM, List.of()).getStatus()).isEqualTo(204);
        assertThat(accountState(admin)).isEqualTo("DELETED");
    }

    @Test
    @DisplayName("TEN-TST-16: 혼자뿐인 조직은 이름을 확인하면 보관·관리자 없음, 가입 요청 거절·링크 끔·초대 취소, 복원은 404")
    void abandonsAloneOrg() throws Exception {
        removeMember();
        UUID requester = newAccount("requester", "requester@example.com");
        insertOrgMembership(requester, "MEMBER", "REQUESTED", false);
        String link = insertInviteLink();
        String invited = insertInvitation(firstTeamId, "friend@example.com", "PENDING", null);

        JsonNode check = check(admin);
        assertThat(check.get("withdrawable").asBoolean()).isTrue();
        assertThat(ids(check, "archiveOrgs", "orgId")).containsExactly(orgId);
        assertThat(ids(check, "archiveTeams", "teamId")).isEmpty();

        assertThat(code(withdraw(admin, CONFIRM, List.of()), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(fields(withdraw(admin, CONFIRM, List.of(new OrgName(UUID.fromString(orgId), "다른 서재")))))
                .containsExactly("orgNameConfirmations:NOT_ALLOWED_VALUE");
        assertThat(fields(withdraw(admin, "탈퇴", confirmed())))
                .containsExactly("confirmText:NOT_ALLOWED_VALUE");
        assertThat(fields(withdraw(admin, CONFIRM, List.of(new OrgName(UUID.fromString(orgId), ORG_NAME),
                new OrgName(UUID.fromString(orgId), ORG_NAME)))))
                .containsExactly("orgNameConfirmations:DUPLICATE");
        assertThat(accountState(admin)).isEqualTo("ACTIVE");

        assertThat(withdraw(admin, CONFIRM, List.of(new OrgName(UUID.fromString(orgId), " " + ORG_NAME + " ")))
                .getStatus()).isEqualTo(204);

        assertThat(jdbc.sql("SELECT state, ownerless FROM organization WHERE id = :id").param("id", orgId)
                .query((rs, rowNum) -> rs.getString("state") + ":" + rs.getBoolean("ownerless")).single())
                .isEqualTo("ARCHIVED:true");
        assertThat(orgMembership(requester)).isEqualTo("REJECTED:-:false");
        assertThat(jdbc.sql("SELECT enabled FROM invite_link WHERE id = :id").param("id", link)
                .query(Boolean.class).single()).isFalse();
        assertThat(invitation(invited)).isEqualTo("REVOKED:friend@example.com");
        assertThat(orgLog("ORG_ARCHIVED")).containsExactly(admin + " " + orgId + " ACTIVE ARCHIVED");
        assertThat(orgLog("ORG_ABANDONED")).containsExactly(admin + " " + orgId + " ACTIVE OWNERLESS");
        assertThat(jdbc.sql("SELECT state FROM team WHERE id = :id").param("id", firstTeamId)
                .query(String.class).single()).isEqualTo("ACTIVE");

        UUID next = newAccount("next", "next@example.com");
        insertOrgMembership(next, "MEMBER", "ACTIVE", true);
        assertThat(perform(post("/api/orgs/" + orgId + "/restore"), next).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("TEN-WD-03: 이미 보관된 조직의 유일한 관리자는 다른 멤버가 있으면 넘겨야 하고, 혼자면 관리자 없음만 표시")
    void handlesArchivedOrg() throws Exception {
        jdbc.sql("UPDATE organization SET state = 'ARCHIVED' WHERE id = :id").param("id", orgId).update();
        JsonNode check = check(admin);
        assertThat(ids(check, "transferOrgs", "orgId")).containsExactly(orgId);
        assertThat(check.get("transferOrgs").get(0).get("state").asString()).isEqualTo("ARCHIVED");

        removeMember();
        assertThat(withdraw(admin, CONFIRM, confirmed()).getStatus()).isEqualTo(204);

        assertThat(jdbc.sql("SELECT state, ownerless FROM organization WHERE id = :id").param("id", orgId)
                .query((rs, rowNum) -> rs.getString("state") + ":" + rs.getBoolean("ownerless")).single())
                .isEqualTo("ARCHIVED:true");
        assertThat(orgLog("ORG_ARCHIVED")).isEmpty();
        assertThat(orgLog("ORG_ABANDONED")).containsExactly(admin + " " + orgId + " ARCHIVED OWNERLESS");
    }

    @Test
    @DisplayName("TEN-WD-04: 혼자뿐인 팀은 함께 보관되고(조직에서 정지된 팀원은 세지 않음), 조직 관리자가 새 팀 관리자를 정해 복원")
    void archivesAloneTeam() throws Exception {
        String solo = createTeam("혼자 팀");
        jdbc.sql("DELETE FROM team_membership WHERE team_id = :team").param("team", solo).update();
        insertTeamMembership(solo, member, "TEAM_ADMIN", "ACTIVE");
        UUID paused = newAccount("paused", "paused@example.com");
        insertOrgMembership(paused, "MEMBER", "SUSPENDED", false);
        insertTeamMembership(solo, paused, "TEAM_MEMBER", "ACTIVE");

        JsonNode check = check(member);
        assertThat(check.get("withdrawable").asBoolean()).isTrue();
        assertThat(ids(check, "archiveTeams", "teamId")).containsExactly(solo);
        assertThat(check.get("archiveTeams").get(0).get("orgName").asString()).isEqualTo(ORG_NAME);

        assertThat(withdraw(member, CONFIRM, List.of()).getStatus()).isEqualTo(204);

        assertThat(jdbc.sql("SELECT state FROM team WHERE id = :id").param("id", solo).query(String.class).single())
                .isEqualTo("ARCHIVED");
        assertThat(orgLog("TEAM_ARCHIVED")).containsExactly(member + " " + solo + " ACTIVE ARCHIVED");
        assertThat(perform(post("/api/orgs/" + orgId + "/teams/" + solo + "/restore")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newAdminAccountId\":\"" + admin + "\"}"), admin).getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("API-ME-02: 확인한 뒤 보관될 조직이 바뀌면 409 ALREADY_PROCESSED")
    void rejectsChangedState() throws Exception {
        removeMember();
        assertThat(ids(check(admin), "archiveOrgs", "orgId")).containsExactly(orgId);

        insertOrgMembership(member, "MEMBER", "ACTIVE", false);
        assertThat(code(withdraw(admin, CONFIRM, confirmed()), 409)).isEqualTo("ALREADY_PROCESSED");

        assertThat(code(withdraw(member, CONFIRM, confirmed()), 409)).isEqualTo("ALREADY_PROCESSED");
        assertThat(accountState(admin)).isEqualTo("ACTIVE");
        assertThat(accountState(member)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("TEN-TST-14: 조직 관리자 둘이 동시에 탈퇴해도 조직 행 잠금으로 둘째는 기다린 뒤 409, 관리자가 남음")
    void keepsAdminsUnderConcurrency() throws Exception {
        jdbc.sql("UPDATE org_membership SET org_admin = :admin WHERE account_id = :account")
                .param("admin", true).param("account", member.toString()).update();
        UUID helper = newAccount("helper", "helper@example.com");
        insertOrgMembership(helper, "MEMBER", "ACTIVE", false);
        insertTeamMembership(firstTeamId, helper, "TEAM_ADMIN", "ACTIVE");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactions.executeWithoutResult(status -> {
            withdrawals.withdraw(admin, CONFIRM, List.of());
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<MockHttpServletResponse> second = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try {
                return withdraw(member, CONFIRM, List.of());
            }
            catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500);
        assertThat(second.isDone()).as("잠금 대기").isFalse();
        release.countDown();
        holder.join();

        assertThat(code(second.get(30, TimeUnit.SECONDS), 409)).isEqualTo("LAST_ADMIN_REQUIRED");
        assertThat(accountState(admin)).isEqualTo("DELETED");
        assertThat(orgMembership(member)).isEqualTo("ACTIVE:-:true");
    }

    private JsonNode check(UUID actor) throws Exception {
        return json(perform(get("/api/me/withdrawal-check"), actor), 200);
    }

    private MockHttpServletResponse withdraw(UUID actor, String confirmText, List<OrgName> orgNames)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("confirmText", confirmText);
        body.put("orgNameConfirmations", orgNames);
        return perform(post("/api/me/withdraw").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)), actor);
    }

    private List<OrgName> confirmed() {
        return List.of(new OrgName(UUID.fromString(orgId), ORG_NAME));
    }

    private static List<String> ids(JsonNode check, String list, String field) {
        List<String> ids = new ArrayList<>();
        check.get(list).forEach(item -> ids.add(item.get(field).asString()));
        return ids;
    }

    private void removeMember() {
        jdbc.sql("DELETE FROM team_membership WHERE account_id = :account").param("account", member.toString())
                .update();
        jdbc.sql("DELETE FROM org_membership WHERE account_id = :account").param("account", member.toString())
                .update();
    }

    private void setOrgState(UUID account, String state) {
        jdbc.sql("UPDATE org_membership SET state = :state WHERE account_id = :account")
                .param("state", state).param("account", account.toString()).update();
    }

    private void setTeamRole(UUID account, String role) {
        jdbc.sql("UPDATE team_membership SET role = :role WHERE team_id = :team AND account_id = :account")
                .param("role", role).param("team", firstTeamId).param("account", account.toString()).update();
    }

    private String accountState(UUID account) {
        return jdbc.sql("SELECT state FROM account WHERE id = :id").param("id", account.toString())
                .query(String.class).single();
    }

    private String createTeam(String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", "PRIVATE");
        body.put("jobTypeId", jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", orgId).query(String.class).single());
        body.put("firstAdminAccountId", admin.toString());
        MockHttpServletResponse response = perform(post("/api/orgs/" + orgId + "/teams")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), admin);
        return json(response, 201).get("teamId").asString();
    }

    private MockHttpServletResponse createOrg(UUID actor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", ORG_NAME);
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

    private void insertOrgMembership(UUID account, String kind, String state, boolean orgAdmin) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            created_at, updated_at, version, admission_review)
                        VALUES (:id, :org, :account, :kind, :admin, :state, 'INVITATION', :now, :now, 0, FALSE)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId)
                .param("account", account.toString())
                .param("kind", kind)
                .param("admin", orgAdmin)
                .param("state", state)
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

    private String insertInvitation(String teamId, String email, String state, UUID acceptedBy) {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO invitation (id, org_id, team_id, email, kind, role, state, accept_by, created_by,
                            accepted_by, created_at, updated_at, version)
                        VALUES (:id, :org, :team, :email, 'MEMBER', :role, :state, :acceptBy, :admin, :acceptedBy,
                            :now, :now, 0)
                        """)
                .param("id", id)
                .param("org", orgId)
                .param("team", teamId)
                .param("email", email)
                .param("role", teamId == null ? null : "TEAM_MEMBER")
                .param("state", state)
                .param("acceptBy", now.plusDays(7))
                .param("admin", admin.toString())
                .param("acceptedBy", acceptedBy == null ? null : acceptedBy.toString())
                .param("now", now)
                .update();
        return id;
    }

    private String insertInviteLink() {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO invite_link (id, org_id, team_id, token_hash, approval_required, role, expires_at,
                            max_uses, use_count, enabled, created_by, created_at, updated_at, version)
                        VALUES (:id, :org, :team, :hash, FALSE, 'TEAM_MEMBER', :expires, 10, 0, TRUE, :admin,
                            :now, :now, 0)
                        """)
                .param("id", id)
                .param("org", orgId)
                .param("team", firstTeamId)
                .param("hash", (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""))
                .param("expires", now.plusDays(7))
                .param("admin", admin.toString())
                .param("now", now)
                .update();
        return id;
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

    private String invitation(String id) {
        return jdbc.sql("SELECT state, email FROM invitation WHERE id = :id").param("id", id)
                .query((rs, rowNum) -> rs.getString("state") + ":"
                        + (rs.getString("email") == null ? "-" : rs.getString("email")))
                .single();
    }

    private String orgMembership(UUID account) {
        return jdbc.sql("""
                        SELECT state, left_reason, org_admin FROM org_membership
                        WHERE org_id = :org AND account_id = :account
                        """)
                .param("org", orgId).param("account", account.toString())
                .query((rs, rowNum) -> rs.getString("state") + ":"
                        + (rs.getString("left_reason") == null ? "-" : rs.getString("left_reason")) + ":"
                        + rs.getBoolean("org_admin"))
                .single();
    }

    private List<String> orgLog(String action) {
        return jdbc.sql("""
                        SELECT actor_id, target_id, before_value, after_value FROM org_activity_log
                        WHERE org_id = :org AND action = :action ORDER BY occurred_at
                        """)
                .param("org", orgId).param("action", action)
                .query((rs, rowNum) -> rs.getString("actor_id") + " " + rs.getString("target_id") + " "
                        + rs.getString("before_value") + " " + rs.getString("after_value"))
                .list();
    }

    private List<String> teamLog(String action) {
        return jdbc.sql("""
                        SELECT team_id, target_id, before_value, after_value FROM team_activity_log
                        WHERE action = :action AND actor_id = target_id ORDER BY occurred_at
                        """)
                .param("action", action)
                .query((rs, rowNum) -> rs.getString("team_id") + " " + rs.getString("target_id") + " "
                        + rs.getString("before_value") + " " + rs.getString("after_value"))
                .list();
    }

    private List<String> fields(MockHttpServletResponse response) throws Exception {
        List<String> fields = new ArrayList<>();
        json(response, 400).get("fields").forEach(error -> fields.add(error.get("field").asString() + ":"
                + error.get("reason").asString()));
        return fields;
    }

    private String code(MockHttpServletResponse response, int status) throws Exception {
        return json(response, status).get("code").asString();
    }

    private JsonNode json(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return jsonMapper.readTree(response.getContentAsString());
    }

    private UUID newAccount(String name, String email) {
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(), email, name,
                null, null), "v1", "v1", Instant.now());
    }

}
