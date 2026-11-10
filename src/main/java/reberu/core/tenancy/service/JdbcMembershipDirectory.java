package reberu.core.tenancy.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.core.access.MembershipDirectory;

class JdbcMembershipDirectory implements MembershipDirectory {

    private final JdbcClient jdbc;

    JdbcMembershipDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrgView> org(UUID orgId, UUID accountId) {
        return jdbc.sql("""
                        SELECT o.id AS org_id, o.state AS org_state, m.state AS member_state, m.kind, m.org_admin,
                               m.expires_at AS member_expires_at
                        FROM organization o
                        LEFT JOIN org_membership m ON m.org_id = o.id AND m.account_id = :account
                        WHERE o.id = :org
                        """)
                .param("org", orgId.toString())
                .param("account", accountId.toString())
                .query((rs, rowNum) -> orgView(rs))
                .optional();
    }

    @Override
    public Optional<TeamView> team(UUID teamId, UUID accountId) {
        return jdbc.sql("""
                        SELECT t.id AS team_id, t.state AS team_state, o.id AS org_id, o.state AS org_state,
                               m.state AS member_state, m.kind, m.org_admin, m.expires_at AS member_expires_at,
                               tm.state AS team_member_state, tm.role, tm.expires_at AS team_member_expires_at
                        FROM team t
                        JOIN organization o ON o.id = t.org_id
                        LEFT JOIN org_membership m ON m.org_id = o.id AND m.account_id = :account
                        LEFT JOIN team_membership tm ON tm.team_id = t.id AND tm.account_id = :account
                        WHERE t.id = :team
                        """)
                .param("team", teamId.toString())
                .param("account", accountId.toString())
                .query((rs, rowNum) -> {
                    String memberState = rs.getString("team_member_state");
                    TeamMember member = memberState == null ? null : new TeamMember("ACTIVE".equals(memberState),
                            Role.valueOf(rs.getString("role")), instant(rs, "team_member_expires_at"));
                    Status status = "ACTIVE".equals(rs.getString("team_state")) ? Status.ACTIVE : Status.READ_ONLY;
                    return new TeamView(UUID.fromString(rs.getString("team_id")), status, orgView(rs), member);
                })
                .optional();
    }

    private static OrgView orgView(ResultSet rs) throws SQLException {
        String memberState = rs.getString("member_state");
        OrgMember member = memberState == null ? null : new OrgMember("ACTIVE".equals(memberState),
                "GUEST".equals(rs.getString("kind")), rs.getBoolean("org_admin"), instant(rs, "member_expires_at"));
        return new OrgView(UUID.fromString(rs.getString("org_id")), orgStatus(rs.getString("org_state")), member);
    }

    private static Status orgStatus(String state) {
        return switch (state) {
            case "ACTIVE" -> Status.ACTIVE;
            case "ARCHIVED", "DELETION_SCHEDULED" -> Status.READ_ONLY;
            default -> Status.CLOSED;
        };
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

}
