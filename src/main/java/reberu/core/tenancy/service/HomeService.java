package reberu.core.tenancy.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.tenancy.HomeScreens;
import reberu.core.tenancy.HomeScreens.HomeNext.Screen;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;

public class HomeService implements HomeScreens {

    private final JdbcClient jdbc;

    HomeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public HomeNext next(UUID accountId) {
        return home(accountId).next();
    }

    @Transactional(readOnly = true)
    public Home home(UUID accountId) {
        String account = accountId.toString();
        Map<UUID, OrgEntry> orgs = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT o.id, o.name, m.org_admin, m.kind, m.expires_at
                        FROM org_membership m
                        JOIN organization o ON o.id = m.org_id
                        WHERE m.account_id = :account AND m.state = 'ACTIVE'
                          AND o.state NOT IN ('SUSPENDED', 'DELETED')
                        ORDER BY m.created_at, o.id
                        """)
                .param("account", account)
                .query((rs, rowNum) -> new OrgEntry(UUID.fromString(rs.getString("id")), rs.getString("name"),
                        rs.getBoolean("org_admin"), "GUEST".equals(rs.getString("kind")),
                        instant(rs.getObject("expires_at", LocalDateTime.class)), new ArrayList<>()))
                .list()
                .forEach(org -> orgs.put(org.orgId(), org));
        List<TeamEntry> activeTeams = new ArrayList<>();
        jdbc.sql("""
                        SELECT t.org_id, t.id, t.name, t.state, j.name AS job_type_name, tm.role, tm.expires_at
                        FROM team_membership tm
                        JOIN team t ON t.id = tm.team_id
                        LEFT JOIN job_type j ON j.id = t.job_type_id AND j.org_id = t.org_id
                        WHERE tm.account_id = :account AND tm.state = 'ACTIVE'
                        ORDER BY tm.created_at, t.id
                        """)
                .param("account", account)
                .query((rs, rowNum) -> new Object[] {
                        UUID.fromString(rs.getString("org_id")),
                        new TeamEntry(UUID.fromString(rs.getString("id")), rs.getString("name"),
                                rs.getString("job_type_name"), TeamRole.valueOf(rs.getString("role")),
                                instant(rs.getObject("expires_at", LocalDateTime.class)),
                                TeamState.valueOf(rs.getString("state")))})
                .list()
                .forEach(row -> {
                    OrgEntry org = orgs.get((UUID) row[0]);
                    if (org != null) {
                        TeamEntry team = (TeamEntry) row[1];
                        org.teams().add(team);
                        if (team.state() == TeamState.ACTIVE) {
                            activeTeams.add(team);
                        }
                    }
                });
        boolean requested = jdbc.sql("""
                        SELECT (SELECT COUNT(*) FROM org_membership WHERE account_id = :account AND state = 'REQUESTED')
                             + (SELECT COUNT(*) FROM team_membership WHERE account_id = :account AND state = 'REQUESTED')
                        """)
                .param("account", account)
                .query(Long.class)
                .single() > 0;
        return new Home(decide(orgs.isEmpty(), activeTeams, requested), List.copyOf(orgs.values()), 0);
    }

    private static HomeNext decide(boolean noOrg, List<TeamEntry> activeTeams, boolean requested) {
        if (activeTeams.size() == 1) {
            return new HomeNext(Screen.TEAM_HOME, activeTeams.get(0).teamId());
        }
        if (activeTeams.size() > 1 || !noOrg) {
            return new HomeNext(Screen.TEAM_PICKER, null);
        }
        return new HomeNext(requested ? Screen.JOIN_PENDING : Screen.START, null);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record Home(HomeNext next, List<OrgEntry> orgs, int invitationCount) {
    }

    public record OrgEntry(UUID orgId, String name, boolean orgAdmin, boolean guest, Instant guestExpiresAt,
            List<TeamEntry> teams) {
    }

    public record TeamEntry(UUID teamId, String name, String jobTypeName, TeamRole role, Instant guestExpiresAt,
            TeamState state) {
    }

}
