package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.account.AccountProfile;
import reberu.core.account.Accounts;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.audit.ActivityRecord;
import reberu.core.audit.TeamActivitySearch;
import reberu.core.support.CursorCodec;
import reberu.core.support.CursorPage;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.JoinedVia;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;

public class TeamMemberService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final List<MembershipState> ADMIN_DEFAULT_STATES = List.of(MembershipState.ACTIVE,
            MembershipState.SUSPENDED, MembershipState.EXPIRED);
    private static final Set<MembershipState> ADMIN_LISTABLE_STATES = Set.of(MembershipState.ACTIVE,
            MembershipState.SUSPENDED, MembershipState.EXPIRED, MembershipState.LEFT);
    private static final Set<TeamRole> ADDABLE_ROLES = Set.of(TeamRole.TEAM_MEMBER, TeamRole.VIEWER);
    private static final Pattern ACTION = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private final OrganizationRepository organizations;
    private final OrgMembershipRepository orgMemberships;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final Accounts accounts;
    private final ActivityLogs activityLogs;
    private final CursorCodec cursors;
    private final JdbcClient jdbc;
    private final Clock clock;

    TeamMemberService(OrganizationRepository organizations, OrgMembershipRepository orgMemberships,
            TeamRepository teams, TeamMembershipRepository teamMemberships, Accounts accounts,
            ActivityLogs activityLogs, CursorCodec cursors, JdbcClient jdbc, Clock clock) {
        this.organizations = organizations;
        this.orgMemberships = orgMemberships;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.accounts = accounts;
        this.activityLogs = activityLogs;
        this.cursors = cursors;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CursorPage<TeamMemberView> list(UUID orgId, UUID teamId, boolean admin, MembershipState state,
            String cursor, int limit) {
        List<MembershipState> states;
        if (!admin) {
            states = List.of(MembershipState.ACTIVE);
        }
        else if (state == null) {
            states = ADMIN_DEFAULT_STATES;
        }
        else if (ADMIN_LISTABLE_STATES.contains(state)) {
            states = List.of(state);
        }
        else {
            throw new ReberuException(ErrorCode.INVALID_REQUEST,
                    List.of(new FieldViolation("state", FieldReason.NOT_ALLOWED_VALUE)));
        }
        String scope = "team-members:" + teamId;
        Position after = cursor == null ? null : Position.of(cursors.decode(scope, cursor));
        String afterFilter = after == null ? ""
                : " AND (m.created_at > :afterAt OR (m.created_at = :afterAt AND m.id > :afterId))";
        JdbcClient.StatementSpec query = jdbc.sql("""
                        SELECT m.id, m.account_id, m.role, m.state, m.expires_at, m.created_at, o.kind
                        FROM team_membership m
                        LEFT JOIN org_membership o ON o.org_id = :org AND o.account_id = m.account_id
                        WHERE m.team_id = :team AND m.state IN (:states)
                        """ + afterFilter + " ORDER BY m.created_at, m.id LIMIT :limit")
                .param("org", orgId.toString())
                .param("team", teamId.toString())
                .param("states", states.stream().map(Enum::name).toList())
                .param("limit", limit + 1);
        if (after != null) {
            query = query.param("afterAt", after.at()).param("afterId", after.id());
        }
        List<Row> rows = query.query((rs, rowNum) -> {
            LocalDateTime expires = rs.getObject("expires_at", LocalDateTime.class);
            String kind = rs.getString("kind");
            return new Row(rs.getString("id"), UUID.fromString(rs.getString("account_id")),
                    TeamRole.valueOf(rs.getString("role")), MembershipState.valueOf(rs.getString("state")),
                    expires == null ? null : expires.toInstant(ZoneOffset.UTC),
                    rs.getObject("created_at", LocalDateTime.class),
                    kind == null ? null : MembershipKind.valueOf(kind));
        }).list();
        List<Row> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        Map<UUID, AccountProfile> profiles = accounts.profiles(page.stream().map(Row::accountId).toList());
        List<TeamMemberView> items = page.stream().map(row -> {
            AccountProfile profile = profiles.get(row.accountId());
            return new TeamMemberView(row.accountId(), profile == null ? null : profile.displayName(),
                    admin && profile != null ? profile.email() : null, row.kind(), row.role(), row.state(),
                    row.createdAt().toInstant(ZoneOffset.UTC), row.expiresAt());
        }).toList();
        if (rows.size() <= limit) {
            return new CursorPage<>(items, null);
        }
        Row last = page.get(limit - 1);
        return new CursorPage<>(items, cursors.encode(scope, List.of(last.createdAt().toString(), last.id())));
    }

    @Transactional
    public void add(UUID orgId, UUID teamId, UUID actorId, UUID accountId, TeamRole role) {
        lock(orgId, teamId, actorId);
        if (role == null || !ADDABLE_ROLES.contains(role)) {
            throw invalid("role", role == null ? FieldReason.REQUIRED : FieldReason.NOT_ALLOWED_VALUE);
        }
        boolean member = orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE
                        && membership.kind() == MembershipKind.MEMBER)
                .isPresent();
        if (!member) {
            throw invalid("accountId", FieldReason.NOT_ALLOWED_VALUE);
        }
        Instant now = clock.instant();
        Optional<TeamMembership> existing = teamMemberships.findByTeamIdAndAccountId(teamId, accountId);
        String before = null;
        if (existing.isPresent()) {
            MembershipState state = existing.get().state();
            if (state != MembershipState.LEFT && state != MembershipState.REJECTED) {
                throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
            }
            before = state.name();
            existing.get().rejoin(role, now);
        }
        else {
            teamMemberships.save(TeamMembership.active(Uuids.v7(), teamId, accountId, role, JoinedVia.DIRECT, now));
        }
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_MEMBER_ADDED", "ACCOUNT", accountId,
                before, role.name()));
    }

    @Transactional
    public void changeRole(UUID orgId, UUID teamId, UUID actorId, UUID accountId, TeamRole role, Integer version) {
        Team team = lock(orgId, teamId, actorId);
        TeamMembership target = target(teamId, accountId);
        if (version == null || version != target.version()) {
            throw new ReberuException(ErrorCode.VERSION_CONFLICT);
        }
        if (role == null) {
            throw invalid("role", FieldReason.REQUIRED);
        }
        if (target.role() == role) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        Optional<OrgMembership> orgMembership = orgMemberships.findByOrgIdAndAccountId(orgId, accountId);
        if (role == TeamRole.TEAM_ADMIN && (target.state() != MembershipState.ACTIVE
                || orgMembership.filter(membership -> membership.state() == MembershipState.ACTIVE
                        && membership.kind() == MembershipKind.MEMBER).isEmpty())) {
            throw invalid("role", FieldReason.NOT_ALLOWED_VALUE);
        }
        if (target.role() == TeamRole.TEAM_ADMIN) {
            keepTeamAdmin(team, target, orgMembership);
        }
        String before = target.role().name();
        target.changeRole(role, clock.instant());
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_ROLE_CHANGED", "ACCOUNT", accountId, before,
                role.name()));
    }

    @Transactional
    public void suspend(UUID orgId, UUID teamId, UUID actorId, UUID accountId) {
        rejectSelf(actorId, accountId);
        Team team = lock(orgId, teamId, actorId);
        TeamMembership target = target(teamId, accountId);
        if (target.state() == MembershipState.SUSPENDED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        keepTeamAdmin(team, target, orgMemberships.findByOrgIdAndAccountId(orgId, accountId));
        target.suspend(clock.instant());
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_MEMBER_SUSPENDED", "ACCOUNT", accountId,
                "ACTIVE", "SUSPENDED"));
    }

    @Transactional
    public void unsuspend(UUID orgId, UUID teamId, UUID actorId, UUID accountId) {
        lock(orgId, teamId, actorId);
        TeamMembership target = target(teamId, accountId);
        if (target.state() == MembershipState.ACTIVE) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        target.unsuspend(clock.instant());
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_MEMBER_UNSUSPENDED", "ACCOUNT", accountId,
                "SUSPENDED", "ACTIVE"));
    }

    @Transactional
    public void remove(UUID orgId, UUID teamId, UUID actorId, UUID accountId) {
        rejectSelf(actorId, accountId);
        Team team = lock(orgId, teamId, actorId);
        TeamMembership target = target(teamId, accountId);
        keepTeamAdmin(team, target, orgMemberships.findByOrgIdAndAccountId(orgId, accountId));
        String before = target.state().name();
        target.remove(clock.instant());
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_MEMBER_REMOVED", "ACCOUNT", accountId,
                before, "LEFT"));
    }

    @Transactional(readOnly = true)
    public CursorPage<TeamActivityView> activity(UUID teamId, ActivityFilter filter, String cursor, int limit) {
        if (filter.action() != null && !ACTION.matcher(filter.action()).matches()) {
            throw invalid("action", FieldReason.INVALID_FORMAT);
        }
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw invalid("to", FieldReason.NOT_ALLOWED_VALUE);
        }
        String scope = "team-activity:" + teamId;
        Instant afterAt = null;
        UUID afterId = null;
        if (cursor != null) {
            List<String> position = cursors.decode(scope, cursor);
            try {
                if (position.size() != 2) {
                    throw new IllegalArgumentException("커서 위치 개수 오류");
                }
                afterAt = Instant.parse(position.get(0));
                afterId = UUID.fromString(position.get(1));
            }
            catch (RuntimeException invalid) {
                throw new ReberuException(ErrorCode.INVALID_REQUEST);
            }
        }
        List<ActivityRecord> records = activityLogs.searchTeam(new TeamActivitySearch(teamId, filter.from(),
                filter.to(), filter.actorId(), filter.action(), afterAt, afterId, limit + 1));
        List<ActivityRecord> page = records.size() > limit ? records.subList(0, limit) : records;
        Set<UUID> accountIds = new HashSet<>();
        for (ActivityRecord record : page) {
            if (record.actorId() != null) {
                accountIds.add(record.actorId());
            }
            if ("ACCOUNT".equals(record.targetType())) {
                accountIds.add(record.targetId());
            }
        }
        Map<UUID, AccountProfile> profiles = accounts.profiles(accountIds);
        List<TeamActivityView> items = page.stream().map(record -> new TeamActivityView(record.id(),
                record.occurredAt(), record.actorId(), name(profiles, record.actorId()), record.action(),
                record.targetType(), record.targetId(),
                "ACCOUNT".equals(record.targetType()) ? name(profiles, record.targetId()) : null,
                record.beforeValue(), record.afterValue())).toList();
        if (records.size() <= limit) {
            return new CursorPage<>(items, null);
        }
        ActivityRecord last = page.get(limit - 1);
        return new CursorPage<>(items, cursors.encode(scope, List.of(last.occurredAt().toString(),
                last.id().toString())));
    }

    private Team lock(UUID orgId, UUID teamId, UUID actorId) {
        organizations.findForUpdate(orgId).orElseThrow(TeamMemberService::notFound);
        Team team = teams.findForUpdate(orgId, teamId).orElseThrow(TeamMemberService::notFound);
        OrgMembership actor = orgMemberships.findByOrgIdAndAccountId(orgId, actorId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE)
                .orElseThrow(TeamMemberService::notFound);
        boolean orgAdmin = actor.orgAdmin() && actor.kind() == MembershipKind.MEMBER;
        boolean teamAdmin = teamMemberships.findByTeamIdAndAccountId(teamId, actorId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE
                        && membership.role() == TeamRole.TEAM_ADMIN)
                .isPresent();
        if (!orgAdmin && !teamAdmin) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        return team;
    }

    private TeamMembership target(UUID teamId, UUID accountId) {
        return teamMemberships.findByTeamIdAndAccountId(teamId, accountId)
                .filter(TeamMembership::current)
                .orElseThrow(TeamMemberService::notFound);
    }

    private void keepTeamAdmin(Team team, TeamMembership target, Optional<OrgMembership> orgMembership) {
        boolean counted = team.state() == TeamState.ACTIVE && target.role() == TeamRole.TEAM_ADMIN
                && target.state() == MembershipState.ACTIVE
                && orgMembership.filter(membership -> membership.state() == MembershipState.ACTIVE).isPresent();
        if (counted && otherActiveAdmins(team, target.accountId()) == 0) {
            throw new ReberuException(ErrorCode.LAST_ADMIN_REQUIRED);
        }
    }

    private long otherActiveAdmins(Team team, UUID accountId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM team_membership m
                        JOIN org_membership o ON o.org_id = :org AND o.account_id = m.account_id
                        WHERE m.team_id = :team AND m.account_id <> :account AND m.state = 'ACTIVE'
                          AND m.role = 'TEAM_ADMIN' AND o.state = 'ACTIVE'
                        """)
                .param("org", team.orgId().toString())
                .param("team", team.id().toString())
                .param("account", accountId.toString())
                .query(Long.class)
                .single();
    }

    private static String name(Map<UUID, AccountProfile> profiles, UUID accountId) {
        AccountProfile profile = accountId == null ? null : profiles.get(accountId);
        return profile == null ? null : profile.displayName();
    }

    private static void rejectSelf(UUID actorId, UUID accountId) {
        if (actorId.equals(accountId)) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST);
        }
    }

    private static ReberuException invalid(String field, FieldReason reason) {
        return new ReberuException(ErrorCode.INVALID_REQUEST, List.of(new FieldViolation(field, reason)));
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    private record Row(String id, UUID accountId, TeamRole role, MembershipState state, Instant expiresAt,
            LocalDateTime createdAt, MembershipKind kind) {
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

    public record ActivityFilter(Instant from, Instant to, UUID actorId, String action) {
    }

    public record TeamMemberView(UUID accountId, String displayName, String email, MembershipKind kind,
            TeamRole role, MembershipState state, Instant joinedAt, Instant expiresAt) {
    }

    public record TeamActivityView(UUID id, Instant occurredAt, UUID actorId, String actorName, String action,
            String targetType, UUID targetId, String targetName, String beforeValue, String afterValue) {
    }

}
