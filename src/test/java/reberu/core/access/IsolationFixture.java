package reberu.core.access;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;

public final class IsolationFixture {

    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log", "team_membership",
            "team", "job_type", "org_membership", "organization", "api_idempotency_key", "account_consent",
            "account");

    public final UUID pado = UUID.randomUUID();
    public final UUID gureum = UUID.randomUUID();
    public final UUID padoSea = UUID.randomUUID();
    public final UUID padoForest = UUID.randomUUID();
    public final UUID gureumSea = UUID.randomUUID();

    public final UUID daon;
    public final UUID haneul;
    public final UUID saebom;
    public final UUID doyun;
    public final UUID iseul;

    private final JdbcClient jdbc;
    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

    private IsolationFixture(JdbcClient jdbc, Accounts accounts) {
        this.jdbc = jdbc;
        daon = account(accounts, "daon");
        haneul = account(accounts, "haneul");
        saebom = account(accounts, "saebom");
        doyun = account(accounts, "doyun");
        iseul = account(accounts, "iseul");
    }

    public static IsolationFixture create(JdbcClient jdbc, Accounts accounts) {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        IsolationFixture fixture = new IsolationFixture(jdbc, accounts);
        fixture.build();
        return fixture;
    }

    private void build() {
        org(pado, "파도");
        org(gureum, "구름");
        team(padoSea, pado, "바다팀", "PUBLIC");
        team(padoForest, pado, "숲팀", "PRIVATE");
        team(gureumSea, gureum, "바다팀", "PRIVATE");

        orgMember(pado, daon, "MEMBER", false, null);
        teamMember(padoSea, daon, "TEAM_ADMIN", null);
        teamMember(padoForest, daon, "TEAM_MEMBER", null);

        orgMember(pado, haneul, "MEMBER", false, null);
        teamMember(padoSea, haneul, "VIEWER", null);
        orgMember(gureum, haneul, "MEMBER", false, null);
        teamMember(gureumSea, haneul, "TEAM_MEMBER", null);

        LocalDateTime guestUntil = now.plusDays(30);
        orgMember(pado, saebom, "GUEST", false, guestUntil);
        teamMember(padoForest, saebom, "TEAM_MEMBER", guestUntil);
        orgMember(gureum, saebom, "MEMBER", true, null);
        teamMember(gureumSea, saebom, "TEAM_ADMIN", null);

        orgMember(pado, doyun, "MEMBER", true, null);
    }

    public void expireGuest() {
        LocalDateTime past = now.minusMinutes(1);
        jdbc.sql("UPDATE org_membership SET expires_at = :past WHERE org_id = :org AND account_id = :account")
                .param("past", past).param("org", pado.toString()).param("account", saebom.toString()).update();
        jdbc.sql("UPDATE team_membership SET expires_at = :past WHERE team_id = :team AND account_id = :account")
                .param("past", past).param("team", padoForest.toString()).param("account", saebom.toString())
                .update();
    }

    private UUID account(Accounts accounts, String name) {
        return accounts.createWithConsent(new GoogleProfile("sub-" + name + "-" + UUID.randomUUID(),
                name + "@example.com", name, null, null), "v1", "v1", Instant.now());
    }

    private void org(UUID id, String name) {
        jdbc.sql("""
                        INSERT INTO organization (id, name, visibility, state, time_zone, join_link_enabled,
                            member_admission_review, join_request_mode, guest_approval_required, guest_default_days,
                            guest_max_days, guest_orgspace_allowed, invitation_ttl_days, ownerless, created_at,
                            updated_at, version)
                        VALUES (:id, :name, 'PRIVATE', 'ACTIVE', 'Asia/Seoul', :no, :yes, 'OFF', :yes, 30, 90, :no,
                            7, :no, :now, :now, 0)
                        """)
                .param("id", id.toString()).param("name", name).param("no", false).param("yes", true)
                .param("now", now).update();
    }

    private void team(UUID id, UUID orgId, String name, String visibility) {
        jdbc.sql("""
                        INSERT INTO team (id, org_id, name, name_key, visibility, join_mode, state,
                            report_week_start, report_notice_titles, created_at, updated_at, version)
                        VALUES (:id, :org, :name, :name, :visibility, 'APPROVAL', 'ACTIVE', 1, '[]', :now, :now, 0)
                        """)
                .param("id", id.toString()).param("org", orgId.toString()).param("name", name)
                .param("visibility", visibility).param("now", now).update();
    }

    private void orgMember(UUID orgId, UUID account, String kind, boolean admin, LocalDateTime expiresAt) {
        jdbc.sql("""
                        INSERT INTO org_membership (id, org_id, account_id, kind, org_admin, state, joined_via,
                            expires_at, created_at, updated_at, version)
                        VALUES (:id, :org, :account, :kind, :admin, 'ACTIVE', 'INVITATION', :expires, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString()).param("org", orgId.toString())
                .param("account", account.toString()).param("kind", kind).param("admin", admin)
                .param("expires", expiresAt).param("now", now).update();
    }

    private void teamMember(UUID teamId, UUID account, String role, LocalDateTime expiresAt) {
        jdbc.sql("""
                        INSERT INTO team_membership (id, team_id, account_id, role, state, joined_via, expires_at,
                            created_at, updated_at, version)
                        VALUES (:id, :team, :account, :role, 'ACTIVE', 'DIRECT', :expires, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString()).param("team", teamId.toString())
                .param("account", account.toString()).param("role", role).param("expires", expiresAt)
                .param("now", now).update();
    }

}
