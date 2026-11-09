package reberu.core.tenancy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.MockMvc;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import reberu.core.auth.service.LoginService;
import reberu.core.tenancy.DeploymentMode;
import reberu.core.tenancy.OrgBootstrap;

@SpringBootTest(properties = {
    "reberu.tenancy.mode=SINGLE_ORG",
    "reberu.tenancy.single-org.name=새벽 독서 모임",
    "reberu.tenancy.single-org.time-zone=Asia/Seoul",
    "reberu.tenancy.single-org.admin-email=Leader@Example.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SingleOrgBootstrapTest {

    private static final String ORG_ID = SingleOrgBootstrap.BOOTSTRAP_ORG_ID.toString();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    OrgBootstrap bootstrap;

    @Autowired
    SingleOrgBootstrap runner;

    @Autowired
    LoginService loginService;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM org_membership").update();
        jdbc.sql("DELETE FROM org_activity_log WHERE action = 'ORG_ADMIN_BOOTSTRAPPED'").update();
        jdbc.sql("DELETE FROM account_consent").update();
        jdbc.sql("DELETE FROM account").update();
    }

    @Test
    @DisplayName("TEN-MODE-03: 시작할 때 조직 하나를 비공개로 만들고 기본 직무 유형 복사, 서버 행위로 기록")
    void createsSingleOrganizationOnStartup() {
        Map<String, Object> org = jdbc.sql("SELECT * FROM organization").query().singleRow();

        assertThat(org).containsEntry("id", ORG_ID).containsEntry("name", "새벽 독서 모임")
                .containsEntry("time_zone", "Asia/Seoul").containsEntry("visibility", "PRIVATE")
                .containsEntry("state", "ACTIVE");
        assertThat(org.get("created_by")).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM job_type WHERE org_id = :org").param("org", ORG_ID)
                .query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM org_activity_log WHERE org_id = :org AND action = 'ORG_CREATED'"
                        + " AND actor_id IS NULL")
                .param("org", ORG_ID).query(Integer.class).single()).isOne();
    }

    @Test
    @DisplayName("다시 시작해도 조직은 그대로 하나")
    void restartKeepsSingleOrganization() throws Exception {
        runner.run(null);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM organization").query(Integer.class).single()).isOne();
    }

    @Test
    @DisplayName("TEN-MODE-03: 조직이 둘 이상이면 시작 중단")
    void refusesToStartWithTwoOrganizations() {
        String extra = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO organization (id, name, visibility, state, time_zone, join_link_enabled,
                            member_admission_review, join_request_mode, guest_approval_required, guest_default_days,
                            guest_max_days, guest_orgspace_allowed, invitation_ttl_days, ownerless, created_at,
                            updated_at, version)
                        VALUES (:id, '다른 조직', 'PRIVATE', 'ACTIVE', 'UTC', false, true, 'OFF', true, 30, 90,
                            false, 7, false, :now, :now, 0)
                        """)
                .param("id", extra).param("now", now).update();
        try {
            assertThatThrownBy(() -> runner.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("2개");
        }
        finally {
            jdbc.sql("DELETE FROM organization WHERE id = :id").param("id", extra).update();
        }
    }

    @Test
    @DisplayName("TEN-MODE-02: SINGLE_ORG에서 조직 만들기·기본 직무 유형 API는 404")
    void organizationApisAreHidden() throws Exception {
        UUID account = newAccount("member", "member@example.com");
        AccountAuthentication signedIn = new AccountAuthentication(account, Instant.now());

        assertThat(mockMvc.perform(post("/api/orgs").with(authentication(signedIn)).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"name\":\"두 번째\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/default-job-types").with(authentication(signedIn)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM organization").query(Integer.class).single()).isOne();
    }

    @Test
    @DisplayName("TEN-BOOT-01·02: 관리자 0명이고 이메일이 같으면 조직 관리자로 지정하고 기록")
    void grantsFirstAdminOnLogin() {
        UUID leader = newAccount("leader", "leader@example.com");

        LoginService.LoginOutcome outcome = loginService.login(profile("leader", "leader@example.com"),
                Instant.now(), null);

        assertThat(outcome.kind()).isEqualTo(LoginService.LoginOutcome.Kind.SIGNED_IN);
        Map<String, Object> membership = jdbc.sql("SELECT * FROM org_membership").query().singleRow();
        assertThat(membership).containsEntry("org_id", ORG_ID).containsEntry("account_id", leader.toString())
                .containsEntry("state", "ACTIVE").containsEntry("kind", "MEMBER")
                .containsEntry("joined_via", "BOOTSTRAP");
        assertThat(bool(membership.get("org_admin"))).isTrue();
        assertThat(jdbc.sql("SELECT actor_id FROM org_activity_log WHERE action = 'ORG_ADMIN_BOOTSTRAPPED'")
                .query(String.class).list()).containsExactly(leader.toString());
    }

    @Test
    @DisplayName("부트스트랩 이메일과 다른 계정은 지정 안 함")
    void ignoresOtherEmail() {
        UUID member = newAccount("member", "member@example.com");

        assertThat(bootstrap.grantFirstAdmin(member, "member@example.com")).isFalse();
        assertThat(bootstrap.grantFirstAdmin(member, "leader@example.com.attacker.test")).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM org_membership").query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("TEN-TST-13: 조직 관리자가 있으면 부트스트랩 이메일은 효과 없음")
    void bootstrapEmailHasNoEffectOnceAdminExists() {
        UUID leader = newAccount("leader", "leader@example.com");
        UUID later = newAccount("later", "later@example.com");
        assertThat(bootstrap.grantFirstAdmin(leader, "leader@example.com")).isTrue();

        assertThat(bootstrap.grantFirstAdmin(later, "leader@example.com")).isFalse();
        assertThat(bootstrap.grantFirstAdmin(leader, "leader@example.com")).isFalse();
        assertThat(jdbc.sql("SELECT account_id FROM org_membership").query(String.class).list())
                .containsExactly(leader.toString());
    }

    @Test
    @DisplayName("SINGLE_ORG 설정 값이 빠지면 시작 실패")
    void requiresSingleOrgSettings() {
        assertThatThrownBy(() -> new TenancyProperties(DeploymentMode.SINGLE_ORG, null, List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TenancyProperties(DeploymentMode.SINGLE_ORG,
                new TenancyProperties.SingleOrg("모임", "Mars/Base", "leader@example.com"), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TenancyProperties(DeploymentMode.SINGLE_ORG,
                new TenancyProperties.SingleOrg("모임", "Asia/Seoul", " "), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new TenancyProperties(null, null, null).mode()).isEqualTo(DeploymentMode.MULTI_ORG);
    }

    private UUID newAccount(String name, String email) {
        return accounts.createWithConsent(profile(name, email), "v1", "v1", Instant.now());
    }

    private static GoogleProfile profile(String name, String email) {
        return new GoogleProfile("sub-" + name, email, name, null, null);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag ? flag : ((Number) value).intValue() != 0;
    }

}
