package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.account.AccountProfile;
import reberu.core.account.Accounts;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.CursorCodec;
import reberu.core.support.CursorPage;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.OrgAdminGranted;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;

public class MemberService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final List<MembershipState> DEFAULT_STATES = List.of(MembershipState.ACTIVE,
            MembershipState.SUSPENDED, MembershipState.EXPIRED);
    private static final Set<MembershipState> LISTABLE_STATES = Set.of(MembershipState.ACTIVE,
            MembershipState.SUSPENDED, MembershipState.EXPIRED, MembershipState.LEFT);

    private final OrganizationRepository organizations;
    private final OrgMembershipRepository orgMemberships;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final Accounts accounts;
    private final ActivityLogs activityLogs;
    private final ApplicationEventPublisher events;
    private final CursorCodec cursors;
    private final JdbcClient jdbc;
    private final Clock clock;

    MemberService(OrganizationRepository organizations, OrgMembershipRepository orgMemberships, TeamRepository teams,
            TeamMembershipRepository teamMemberships, Accounts accounts, ActivityLogs activityLogs,
            ApplicationEventPublisher events, CursorCodec cursors, JdbcClient jdbc, Clock clock) {
        this.organizations = organizations;
        this.orgMemberships = orgMemberships;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.accounts = accounts;
        this.activityLogs = activityLogs;
        this.events = events;
        this.cursors = cursors;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CursorPage<MemberView> list(UUID orgId, MemberFilter filter, String cursor, int limit) {
        if (filter.state() != null && !LISTABLE_STATES.contains(filter.state())) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST,
                    List.of(new FieldViolation("state", FieldReason.NOT_ALLOWED_VALUE)));
        }
        String scope = "org-members:" + orgId;
        Position after = cursor == null ? null : Position.of(cursors.decode(scope, cursor));
        List<String> states = (filter.state() == null ? DEFAULT_STATES : List.of(filter.state())).stream()
                .map(Enum::name).toList();
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.account_id, m.kind, m.state, m.org_admin, m.expires_at, m.created_at
                FROM org_membership m
                WHERE m.org_id = :org AND m.state IN (:states)
                """);
        if (filter.kind() != null) {
            sql.append(" AND m.kind = :kind");
        }
        if (filter.teamId() != null) {
            sql.append("""
                     AND EXISTS (SELECT 1 FROM team_membership tm JOIN team t ON t.id = tm.team_id
                                 WHERE t.org_id = m.org_id AND tm.team_id = :team AND tm.account_id = m.account_id
                                   AND tm.state IN ('ACTIVE', 'SUSPENDED'))
                    """);
        }
        if (after != null) {
            sql.append(" AND (m.created_at > :afterAt OR (m.created_at = :afterAt AND m.id > :afterId))");
        }
        sql.append(" ORDER BY m.created_at, m.id LIMIT :limit");
        JdbcClient.StatementSpec query = jdbc.sql(sql.toString())
                .param("org", orgId.toString())
                .param("states", states)
                .param("limit", limit + 1);
        if (filter.kind() != null) {
            query = query.param("kind", filter.kind().name());
        }
        if (filter.teamId() != null) {
            query = query.param("team", filter.teamId().toString());
        }
        if (after != null) {
            query = query.param("afterAt", after.at()).param("afterId", after.id());
        }
        List<MemberRow> rows = query.query((rs, rowNum) -> new MemberRow(rs.getString("id"),
                UUID.fromString(rs.getString("account_id")), MembershipKind.valueOf(rs.getString("kind")),
                MembershipState.valueOf(rs.getString("state")), rs.getBoolean("org_admin"),
                instant(rs.getObject("expires_at", LocalDateTime.class)),
                rs.getObject("created_at", LocalDateTime.class))).list();
        List<MemberRow> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        List<UUID> accountIds = page.stream().map(MemberRow::accountId).toList();
        Map<UUID, List<MemberTeam>> memberTeams = memberTeams(orgId, accountIds);
        Map<UUID, AccountProfile> profiles = accounts.profiles(accountIds);
        List<MemberView> items = page.stream().map(row -> {
            AccountProfile profile = profiles.get(row.accountId());
            return new MemberView(row.accountId(), profile == null ? null : profile.displayName(),
                    profile == null ? null : profile.email(), row.kind(), row.state(), row.orgAdmin(),
                    row.createdAt().toInstant(ZoneOffset.UTC), row.expiresAt(),
                    profile == null ? null : profile.lastLoginAt(),
                    memberTeams.getOrDefault(row.accountId(), List.of()));
        }).toList();
        if (rows.size() <= limit) {
            return new CursorPage<>(items, null);
        }
        MemberRow last = page.get(limit - 1);
        return new CursorPage<>(items, cursors.encode(scope, List.of(last.createdAt().toString(), last.id())));
    }

    @Transactional
    public void suspend(UUID orgId, UUID actorId, UUID accountId) {
        rejectSelf(actorId, accountId);
        OrgMembership target = lockTarget(orgId, actorId, accountId);
        if (target.state() == MembershipState.SUSPENDED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        keepOrgAdmin(orgId, target);
        target.suspend(clock.instant());
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_MEMBER_SUSPENDED", "ACCOUNT", accountId,
                "ACTIVE", "SUSPENDED"));
    }

    @Transactional
    public void unsuspend(UUID orgId, UUID actorId, UUID accountId) {
        OrgMembership target = lockTarget(orgId, actorId, accountId);
        if (target.state() == MembershipState.ACTIVE) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        target.unsuspend(clock.instant());
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_MEMBER_UNSUSPENDED", "ACCOUNT", accountId,
                "SUSPENDED", "ACTIVE"));
    }

    @Transactional
    public void remove(UUID orgId, UUID actorId, UUID accountId) {
        rejectSelf(actorId, accountId);
        OrgMembership target = lockTarget(orgId, actorId, accountId);
        keepOrgAdmin(orgId, target);
        List<TeamMembership> memberships = teamMemberships.findOpenByOrgIdAndAccountId(orgId, accountId);
        memberships.stream()
                .filter(membership -> membership.state() == MembershipState.ACTIVE
                        && membership.role() == TeamRole.TEAM_ADMIN)
                .map(TeamMembership::teamId)
                .sorted()
                .forEach(teamId -> teams.findForUpdate(orgId, teamId));
        if (lastTeamAdmin(orgId, accountId)) {
            throw new ReberuException(ErrorCode.LAST_ADMIN_REQUIRED);
        }
        Instant now = clock.instant();
        String before = target.state().name();
        target.remove(now);
        for (TeamMembership membership : memberships) {
            String teamBefore = membership.state().name();
            membership.remove(now);
            activityLogs.recordTeam(membership.teamId(), new ActivityEntry(actorId, "TEAM_MEMBER_REMOVED", "ACCOUNT",
                    accountId, teamBefore, "LEFT"));
        }
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_MEMBER_REMOVED", "ACCOUNT", accountId, before,
                "LEFT"));
    }

    @Transactional
    public void grantAdmin(UUID orgId, UUID actorId, UUID accountId) {
        OrgMembership target = lockTarget(orgId, actorId, accountId);
        if (target.orgAdmin()) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        if (!target.canBecomeAdmin()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST);
        }
        target.grantAdmin(clock.instant());
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_ADMIN_GRANTED", "ACCOUNT", accountId, null,
                null));
        events.publishEvent(new OrgAdminGranted(orgId, accountId));
    }

    @Transactional
    public void revokeAdmin(UUID orgId, UUID actorId, UUID accountId) {
        OrgMembership target = lockTarget(orgId, actorId, accountId);
        if (!target.orgAdmin()) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        keepOrgAdmin(orgId, target);
        target.revokeAdmin(clock.instant());
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_ADMIN_REVOKED", "ACCOUNT", accountId, null,
                null));
    }

    private OrgMembership lockTarget(UUID orgId, UUID actorId, UUID accountId) {
        organizations.findForUpdate(orgId).orElseThrow(MemberService::notFound);
        OrgMembership actor = orgMemberships.findByOrgIdAndAccountId(orgId, actorId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE)
                .orElseThrow(MemberService::notFound);
        if (!actor.orgAdmin()) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        return orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .filter(OrgMembership::current)
                .orElseThrow(MemberService::notFound);
    }

    private void keepOrgAdmin(UUID orgId, OrgMembership target) {
        if (target.orgAdmin() && target.state() == MembershipState.ACTIVE
                && orgMemberships.countByOrgIdAndOrgAdminTrueAndStateAndAccountIdNot(orgId, MembershipState.ACTIVE,
                        target.accountId()) == 0) {
            throw new ReberuException(ErrorCode.LAST_ADMIN_REQUIRED);
        }
    }

    private boolean lastTeamAdmin(UUID orgId, UUID accountId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM team t
                        JOIN team_membership m ON m.team_id = t.id
                        WHERE t.org_id = :org AND t.state = 'ACTIVE' AND m.account_id = :account
                          AND m.state = 'ACTIVE' AND m.role = 'TEAM_ADMIN'
                          AND NOT EXISTS (
                              SELECT 1 FROM team_membership o
                              JOIN org_membership om ON om.org_id = t.org_id AND om.account_id = o.account_id
                              WHERE o.team_id = t.id AND o.account_id <> :account AND o.state = 'ACTIVE'
                                AND o.role = 'TEAM_ADMIN' AND om.state = 'ACTIVE')
                        """)
                .param("org", orgId.toString())
                .param("account", accountId.toString())
                .query(Long.class)
                .single() > 0;
    }

    private Map<UUID, List<MemberTeam>> memberTeams(UUID orgId, List<UUID> accountIds) {
        Map<UUID, List<MemberTeam>> result = new LinkedHashMap<>();
        if (accountIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT m.account_id, t.id, t.name, m.role, m.state
                        FROM team_membership m
                        JOIN team t ON t.id = m.team_id
                        WHERE t.org_id = :org AND m.account_id IN (:accounts) AND m.state IN ('ACTIVE', 'SUSPENDED')
                        ORDER BY t.created_at, t.id
                        """)
                .param("org", orgId.toString())
                .param("accounts", accountIds.stream().map(UUID::toString).toList())
                .query(rs -> {
                    result.computeIfAbsent(UUID.fromString(rs.getString("account_id")), key -> new ArrayList<>())
                            .add(new MemberTeam(UUID.fromString(rs.getString("id")), rs.getString("name"),
                                    TeamRole.valueOf(rs.getString("role")),
                                    MembershipState.valueOf(rs.getString("state"))));
                });
        return result;
    }

    private static void rejectSelf(UUID actorId, UUID accountId) {
        if (actorId.equals(accountId)) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST);
        }
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    private record MemberRow(String id, UUID accountId, MembershipKind kind, MembershipState state,
            boolean orgAdmin, Instant expiresAt, LocalDateTime createdAt) {
    }

    private record Position(LocalDateTime at, String id) {

        static Position of(List<String> values) {
            try {
                if (values.size() == 2) {
                    String id = values.get(1);
                    if (UUID.fromString(id).toString().equals(id)) {
                        return new Position(LocalDateTime.parse(values.get(0)), id);
                    }
                }
            }
            catch (RuntimeException invalid) {
                throw new ReberuException(ErrorCode.INVALID_REQUEST);
            }
            throw new ReberuException(ErrorCode.INVALID_REQUEST);
        }

    }

    public record MemberFilter(MembershipState state, MembershipKind kind, UUID teamId) {
    }

    public record MemberTeam(UUID teamId, String name, TeamRole role, MembershipState state) {
    }

    public record MemberView(UUID accountId, String displayName, String email, MembershipKind kind,
            MembershipState state, boolean orgAdmin, Instant joinedAt, Instant expiresAt, Instant lastLoginAt,
            List<MemberTeam> teams) {
    }

}
