package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.entity.TenancyCodes.CustomFieldKind;
import reberu.core.tenancy.entity.TenancyCodes.CustomFieldTarget;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.service.DefaultJobTypes.JobTypeDefaults;
import reberu.core.tenancy.service.JobTypeService;
import reberu.core.tenancy.service.JobTypeService.JobTypeChange;
import reberu.core.tenancy.service.JobTypeService.NewJobType;
import reberu.core.tenancy.service.MemberService;
import reberu.core.tenancy.service.OrgCreationService;
import reberu.core.tenancy.service.OrgCreationService.NewOrg;
import reberu.core.tenancy.service.OrgSettingsService;
import reberu.core.tenancy.service.TeamFieldService;
import reberu.core.tenancy.service.TeamFieldService.FieldChange;
import reberu.core.tenancy.service.TeamFieldService.LabelChange;
import reberu.core.tenancy.service.TeamFieldService.NewField;
import reberu.core.tenancy.service.TeamService;
import reberu.core.tenancy.service.TeamService.NewTeam;
import reberu.core.tenancy.service.TeamSettingsService;
import reberu.core.tenancy.service.TeamSettingsService.SettingsChange;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StateRecheckTest {

    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "team_custom_field",
            "team_task_label", "team_membership", "team", "job_type", "org_membership", "organization",
            "api_idempotency_key", "account_consent", "account");
    private static final List<String> WATCHED = List.of("org_membership", "job_type", "team", "team_membership",
            "team_task_label", "team_custom_field", "org_activity_log", "team_activity_log");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    OrgCreationService orgCreation;

    @Autowired
    MemberService members;

    @Autowired
    JobTypeService jobTypes;

    @Autowired
    TeamService teams;

    @Autowired
    TeamSettingsService teamSettings;

    @Autowired
    TeamFieldService teamFields;

    @Autowired
    OrgSettingsService orgSettings;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID admin;
    private UUID member;
    private UUID suspended;
    private UUID otherAdmin;
    private UUID orgId;
    private UUID teamId;
    private UUID archivedTeamId;
    private UUID jobTypeId;
    private UUID hiddenJobTypeId;
    private UUID labelId;
    private UUID fieldId;

    @BeforeEach
    void setUp() {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = newAccount("admin");
        member = newAccount("member");
        suspended = newAccount("suspended");
        otherAdmin = newAccount("otheradmin");
        OrgCreationService.CreatedOrg created = orgCreation.create(admin,
                new NewOrg("새벽 서재", null, Visibility.PRIVATE, "Asia/Seoul", "첫 팀", "basic"));
        orgId = created.orgId();
        teamId = created.teamId();
        insertOrgMembership(member, "ACTIVE", false);
        insertOrgMembership(suspended, "SUSPENDED", false);
        insertOrgMembership(otherAdmin, "ACTIVE", true);
        jobTypeId = UUID.fromString(jdbc.sql("SELECT id FROM job_type WHERE org_id = :org AND name = '기본'")
                .param("org", orgId.toString()).query(String.class).single());
        archivedTeamId = teams.create(orgId, admin, new NewTeam("지난 팀", Visibility.PRIVATE, jobTypeId, admin));
        teams.archive(orgId, archivedTeamId, admin);
        hiddenJobTypeId = jobTypes.create(orgId, admin,
                new NewJobType("숨긴 유형", "설명", new JobTypeDefaults(List.of(), List.of(), List.of(), 1)));
        jobTypes.hide(orgId, hiddenJobTypeId, admin);
        labelId = teamFields.createLabel(orgId, teamId, admin, "검토 대기");
        fieldId = teamFields.createField(orgId, teamId, admin,
                new NewField(CustomFieldTarget.TASK, "메모", CustomFieldKind.TEXT, false));
    }

    @Test
    @DisplayName("TEN-CTX-09: 권한 판정 뒤 잠그기 전에 조직이 보관되면 잠근 뒤 다시 확인해 READ_ONLY, 데이터는 그대로")
    void rechecksOrgStateAfterLocking() {
        setState("organization", orgId, "ARCHIVED");
        Map<String, List<Map<String, Object>>> before = snapshot();

        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("member.suspend", () -> members.suspend(orgId, admin, member));
        calls.put("member.unsuspend", () -> members.unsuspend(orgId, admin, suspended));
        calls.put("member.remove", () -> members.remove(orgId, admin, member));
        calls.put("member.grantAdmin", () -> members.grantAdmin(orgId, admin, member));
        calls.put("member.revokeAdmin", () -> members.revokeAdmin(orgId, admin, otherAdmin));
        calls.put("jobType.create", () -> jobTypes.create(orgId, admin,
                new NewJobType("새 유형", "설명", new JobTypeDefaults(List.of(), List.of(), List.of(), 1))));
        calls.put("jobType.update", () -> jobTypes.update(orgId, jobTypeId, admin,
                new JobTypeChange(version("job_type", jobTypeId), "바꾼 유형", null, null)));
        calls.put("jobType.hide", () -> jobTypes.hide(orgId, jobTypeId, admin));
        calls.put("jobType.unhide", () -> jobTypes.unhide(orgId, hiddenJobTypeId, admin));
        calls.put("team.create", () -> teams.create(orgId, admin,
                new NewTeam("새 팀", Visibility.PRIVATE, jobTypeId, admin)));
        calls.put("team.archive", () -> teams.archive(orgId, teamId, admin));
        calls.put("team.restore", () -> teams.restore(orgId, archivedTeamId, admin, null));
        calls.putAll(teamWrites());
        calls.forEach((name, call) -> assertThat(readOnly(call)).as(name).isTrue());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("TEN-CTX-09: 권한 판정 뒤 잠그기 전에 팀이 보관되면 잠근 뒤 다시 확인해 READ_ONLY, 데이터는 그대로")
    void rechecksTeamStateAfterLocking() {
        setState("team", teamId, "ARCHIVED");
        Map<String, List<Map<String, Object>>> before = snapshot();

        teamWrites().forEach((name, call) -> assertThat(readOnly(call)).as(name).isTrue());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("TEN-CTX-09: 조직 보관이 조직 행을 잡고 있으면 쓰기는 기다렸다가 보관이 끝난 뒤 READ_ONLY, 데이터는 그대로")
    void waitsForArchiveAndRejects() throws Exception {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        int jobTypeVersion = version("job_type", jobTypeId);
        int teamVersion = version("team", teamId);
        calls.put("jobType.update", () -> jobTypes.update(orgId, jobTypeId, admin,
                new JobTypeChange(jobTypeVersion, "경합 유형", null, null)));
        calls.put("teamSettings.update", () -> teamSettings.update(orgId, teamId, admin,
                new SettingsChange(teamVersion, "경합 팀", null, null, null, null)));

        for (Map.Entry<String, Runnable> call : calls.entrySet()) {
            setState("organization", orgId, "ACTIVE");
            List<Map<String, Object>> jobTypesBefore = rows("job_type");
            List<Map<String, Object>> teamsBefore = rows("team");
            Held archive = hold(() -> orgSettings.archive(orgId, admin));

            CompletableFuture<Boolean> write = startBlocked(() -> readOnly(call.getValue()),
                    call.getKey() + " 조직 행 대기");
            archive.finish();

            assertThat(write.get(30, TimeUnit.SECONDS)).as(call.getKey()).isTrue();
            assertThat(rows("job_type")).as(call.getKey()).isEqualTo(jobTypesBefore);
            assertThat(rows("team")).as(call.getKey()).isEqualTo(teamsBefore);
        }
    }

    @Test
    @DisplayName("TEN-CTX-09: 쓰기가 조직 행을 잡고 있으면 팀·조직 보관은 쓰기가 끝날 때까지 대기, 쓰기와 보관 모두 반영")
    void archiveWaitsForWrite() throws Exception {
        Held teamWrite = hold(() -> teamSettings.update(orgId, teamId, admin,
                new SettingsChange(version("team", teamId), "먼저 바꾼 팀", null, null, null, null)));
        CompletableFuture<Boolean> teamArchive = startBlocked(() -> {
            teams.archive(orgId, teamId, admin);
            return true;
        }, "team.archive 조직 행 대기");
        teamWrite.finish();
        assertThat(teamArchive.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(value("team", teamId, "name")).isEqualTo("먼저 바꾼 팀");
        assertThat(value("team", teamId, "state")).isEqualTo("ARCHIVED");

        Held jobTypeWrite = hold(() -> jobTypes.update(orgId, jobTypeId, admin,
                new JobTypeChange(version("job_type", jobTypeId), "먼저 바꾼 유형", null, null)));
        CompletableFuture<Boolean> orgArchive = startBlocked(() -> {
            orgSettings.archive(orgId, admin);
            return true;
        }, "orgSettings.archive 조직 행 대기");
        jobTypeWrite.finish();
        assertThat(orgArchive.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(value("job_type", jobTypeId, "name")).isEqualTo("먼저 바꾼 유형");
        assertThat(value("organization", orgId, "state")).isEqualTo("ARCHIVED");
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

    private Map<String, Runnable> teamWrites() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("teamSettings.update", () -> teamSettings.update(orgId, teamId, admin,
                new SettingsChange(version("team", teamId), "바꾼 팀", null, null, null, null)));
        calls.put("teamFields.createLabel", () -> teamFields.createLabel(orgId, teamId, admin, "새 라벨"));
        calls.put("teamFields.updateLabel", () -> teamFields.updateLabel(orgId, teamId, labelId, admin,
                new LabelChange(version("team_task_label", labelId), "바꾼 라벨", null, null)));
        calls.put("teamFields.createField", () -> teamFields.createField(orgId, teamId, admin,
                new NewField(CustomFieldTarget.TASK, "새 항목", CustomFieldKind.TEXT, false)));
        calls.put("teamFields.updateField", () -> teamFields.updateField(orgId, teamId, fieldId, admin,
                new FieldChange(version("team_custom_field", fieldId), "바꾼 항목", null, null, null)));
        return calls;
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : WATCHED) {
            result.put(table, rows(table));
        }
        return result;
    }

    private List<Map<String, Object>> rows(String table) {
        return jdbc.sql("SELECT * FROM " + table + " ORDER BY id").query().listOfRows();
    }

    private void setState(String table, UUID id, String state) {
        jdbc.sql("UPDATE " + table + " SET state = :state WHERE id = :id")
                .param("state", state).param("id", id.toString()).update();
    }

    private String value(String table, UUID id, String column) {
        return jdbc.sql("SELECT " + column + " FROM " + table + " WHERE id = :id").param("id", id.toString())
                .query(String.class).single();
    }

    private int version(String table, UUID id) {
        return jdbc.sql("SELECT version FROM " + table + " WHERE id = :id").param("id", id.toString())
                .query(Integer.class).single();
    }

    private void insertOrgMembership(UUID account, String state, boolean orgAdmin) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version)
                        VALUES (:id, :org, :account, 'MEMBER', :admin, :state, 'INVITATION', NULL, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("org", orgId.toString())
                .param("account", account.toString())
                .param("admin", orgAdmin)
                .param("state", state)
                .param("now", now)
                .update();
    }

    private UUID newAccount(String name) {
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(),
                name + "@example.com", name, null, null), "v1", "v1", Instant.now());
    }

    private static boolean readOnly(Runnable call) {
        try {
            call.run();
            return false;
        }
        catch (ReberuException rejected) {
            return rejected.code() == ErrorCode.READ_ONLY;
        }
    }

    private record Held(Thread holder, CountDownLatch release) {

        void finish() throws InterruptedException {
            release.countDown();
            holder.join();
        }

    }

}
