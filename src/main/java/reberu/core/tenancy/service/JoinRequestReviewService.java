package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.InviteLink;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.JoinedVia;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.repository.InviteLinkRepository;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;

public class JoinRequestReviewService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final Set<TeamRole> PLACEABLE_ROLES = Set.of(TeamRole.TEAM_MEMBER, TeamRole.VIEWER);

    private final StateLocks locks;
    private final TeamRepository teams;
    private final OrgMembershipRepository orgMemberships;
    private final TeamMembershipRepository teamMemberships;
    private final InviteLinkRepository links;
    private final Accounts accounts;
    private final ActivityLogs activityLogs;
    private final CursorCodec cursors;
    private final JdbcClient jdbc;
    private final Clock clock;

    JoinRequestReviewService(StateLocks locks, TeamRepository teams, OrgMembershipRepository orgMemberships,
            TeamMembershipRepository teamMemberships, InviteLinkRepository links, Accounts accounts,
            ActivityLogs activityLogs, CursorCodec cursors, JdbcClient jdbc, Clock clock) {
        this.locks = locks;
        this.teams = teams;
        this.orgMemberships = orgMemberships;
        this.teamMemberships = teamMemberships;
        this.links = links;
        this.accounts = accounts;
        this.activityLogs = activityLogs;
        this.cursors = cursors;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CursorPage<OrgRequestView> listOrg(UUID orgId, String cursor, int limit) {
        String scope = "org-join-requests:" + orgId;
        Position after = cursor == null ? null : Position.of(cursors.decode(scope, cursor));
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.account_id, m.joined_via, m.updated_at
                FROM org_membership m
                WHERE m.org_id = :org AND m.state = 'REQUESTED' AND m.admission_review = TRUE
                """);
        if (after != null) {
            sql.append(" AND (m.updated_at > :afterAt OR (m.updated_at = :afterAt AND m.id > :afterId))");
        }
        sql.append(" ORDER BY m.updated_at, m.id LIMIT :limit");
        JdbcClient.StatementSpec query = jdbc.sql(sql.toString())
                .param("org", orgId.toString())
                .param("limit", limit + 1);
        if (after != null) {
            query = query.param("afterAt", after.at()).param("afterId", after.id());
        }
        List<RequestRow> rows = query.query((rs, rowNum) -> new RequestRow(rs.getString("id"),
                UUID.fromString(rs.getString("account_id")), JoinedVia.valueOf(rs.getString("joined_via")), null,
                false, rs.getObject("updated_at", LocalDateTime.class))).list();
        List<RequestRow> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        List<UUID> accountIds = page.stream().map(RequestRow::accountId).toList();
        Map<UUID, AccountProfile> profiles = accounts.profiles(accountIds);
        Map<UUID, List<RequestedTeam>> requestedTeams = requestedTeams(orgId, accountIds);
        List<OrgRequestView> items = page.stream()
                .map(row -> new OrgRequestView(row.accountId(), name(profiles, row.accountId()), row.joinedVia(),
                        row.requestedAt().toInstant(ZoneOffset.UTC),
                        requestedTeams.getOrDefault(row.accountId(), List.of())))
                .toList();
        return page(scope, rows, page, limit, items);
    }

    @Transactional(readOnly = true)
    public CursorPage<TeamRequestView> listTeam(UUID orgId, UUID teamId, String cursor, int limit) {
        String scope = "team-join-requests:" + teamId;
        Position after = cursor == null ? null : Position.of(cursors.decode(scope, cursor));
        StringBuilder sql = new StringBuilder("""
                SELECT tm.id, tm.account_id, tm.joined_via, tm.role, tm.updated_at,
                       CASE WHEN m.state = 'REQUESTED' AND m.admission_review = TRUE THEN 1 ELSE 0 END AS org_review
                FROM team_membership tm
                LEFT JOIN org_membership m ON m.org_id = :org AND m.account_id = tm.account_id
                WHERE tm.team_id = :team AND tm.state = 'REQUESTED'
                """);
        if (after != null) {
            sql.append(" AND (tm.updated_at > :afterAt OR (tm.updated_at = :afterAt AND tm.id > :afterId))");
        }
        sql.append(" ORDER BY tm.updated_at, tm.id LIMIT :limit");
        JdbcClient.StatementSpec query = jdbc.sql(sql.toString())
                .param("org", orgId.toString())
                .param("team", teamId.toString())
                .param("limit", limit + 1);
        if (after != null) {
            query = query.param("afterAt", after.at()).param("afterId", after.id());
        }
        List<RequestRow> rows = query.query((rs, rowNum) -> new RequestRow(rs.getString("id"),
                UUID.fromString(rs.getString("account_id")), JoinedVia.valueOf(rs.getString("joined_via")),
                TeamRole.valueOf(rs.getString("role")), rs.getInt("org_review") == 1,
                rs.getObject("updated_at", LocalDateTime.class))).list();
        List<RequestRow> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        Map<UUID, AccountProfile> profiles = accounts.profiles(page.stream().map(RequestRow::accountId).toList());
        List<TeamRequestView> items = page.stream()
                .map(row -> new TeamRequestView(row.accountId(), name(profiles, row.accountId()), row.role(),
                        row.joinedVia(), row.requestedAt().toInstant(ZoneOffset.UTC), row.orgReviewPending()))
                .toList();
        return page(scope, rows, page, limit, items);
    }

    @Transactional
    public void approveOrg(UUID orgId, UUID actorId, UUID accountId, UUID placeTeamId, TeamRole placeRole) {
        locks.activeOrg(orgId);
        requireOrgAdmin(orgId, actorId);
        OrgMembership request = orgRequest(orgId, accountId);
        if (placeTeamId == null && placeRole != null) {
            throw invalid("role", FieldReason.NOT_ALLOWED_VALUE);
        }
        TeamRole role = placeRole == null ? TeamRole.TEAM_MEMBER : placeRole;
        if (!PLACEABLE_ROLES.contains(role)) {
            throw invalid("role", FieldReason.NOT_ALLOWED_VALUE);
        }
        List<TeamMembership> linked = teamMemberships.findOpenByOrgIdAndAccountId(orgId, accountId).stream()
                .filter(membership -> membership.state() == MembershipState.REQUESTED)
                .toList();
        List<UUID> teamIds = new ArrayList<>(linked.stream().map(TeamMembership::teamId).toList());
        if (placeTeamId != null && !teamIds.contains(placeTeamId)) {
            teamIds.add(placeTeamId);
        }
        teamIds.sort(Comparator.naturalOrder());
        Map<UUID, Team> locked = new LinkedHashMap<>();
        for (UUID teamId : teamIds) {
            teams.findForUpdate(orgId, teamId).ifPresent(team -> locked.put(teamId, team));
        }
        Instant now = clock.instant();
        Optional<TeamMembership> placed = Optional.empty();
        if (placeTeamId != null) {
            Team team = locked.get(placeTeamId);
            if (team == null || team.state() != TeamState.ACTIVE) {
                throw invalid("teamId", FieldReason.NOT_ALLOWED_VALUE);
            }
            placed = teamMemberships.findByTeamIdAndAccountId(placeTeamId, accountId);
            if (placed.filter(membership -> membership.state() == MembershipState.SUSPENDED).isPresent()) {
                throw invalid("teamId", FieldReason.NOT_ALLOWED_VALUE);
            }
        }
        request.admit(MembershipKind.MEMBER, null, now);
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_MEMBER_APPROVED", "ACCOUNT", accountId,
                MembershipState.REQUESTED.name(), MembershipKind.MEMBER.name()));
        for (TeamMembership membership : linked) {
            if (membership.teamId().equals(placeTeamId)) {
                continue;
            }
            Optional<InviteLink> link = Optional.ofNullable(membership.inviteLinkId())
                    .flatMap(linkId -> links.findByTeamIdAndId(membership.teamId(), linkId));
            if (link.isPresent() && !link.get().approvalRequired()) {
                membership.admitByLink(membership.role(), membership.inviteLinkId(), now);
                activityLogs.recordTeam(membership.teamId(), new ActivityEntry(actorId, "TEAM_MEMBER_APPROVED",
                        "ACCOUNT", accountId, MembershipState.REQUESTED.name(), membership.role().name()));
            }
        }
        if (placeTeamId != null) {
            place(placeTeamId, accountId, actorId, role, placed, now);
        }
    }

    @Transactional
    public void rejectOrg(UUID orgId, UUID actorId, UUID accountId) {
        locks.activeOrg(orgId);
        requireOrgAdmin(orgId, actorId);
        OrgMembership request = orgRequest(orgId, accountId);
        List<TeamMembership> linked = teamMemberships.findOpenByOrgIdAndAccountId(orgId, accountId).stream()
                .filter(membership -> membership.state() == MembershipState.REQUESTED)
                .toList();
        linked.stream().map(TeamMembership::teamId).sorted().forEach(teamId -> teams.findForUpdate(orgId, teamId));
        Instant now = clock.instant();
        request.reject(now);
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_MEMBER_REJECTED", "ACCOUNT", accountId,
                MembershipState.REQUESTED.name(), MembershipState.REJECTED.name()));
        for (TeamMembership membership : linked) {
            membership.rejectWithoutCooldown(now);
            activityLogs.recordTeam(membership.teamId(), new ActivityEntry(actorId, "TEAM_MEMBER_REJECTED", "ACCOUNT",
                    accountId, MembershipState.REQUESTED.name(), MembershipState.REJECTED.name()));
        }
    }

    @Transactional
    public void approveTeam(UUID orgId, UUID teamId, UUID actorId, UUID accountId) {
        locks.activeTeam(orgId, teamId);
        requireTeamAdmin(orgId, teamId, actorId);
        TeamMembership request = teamRequest(teamId, accountId);
        OrgMembership orgMembership = orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .orElseThrow(JoinRequestReviewService::notFound);
        boolean orgWaiting = orgMembership.state() == MembershipState.REQUESTED;
        if (orgWaiting && orgMembership.admissionReview()) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        if (!orgWaiting && orgMembership.state() != MembershipState.ACTIVE) {
            throw notFound();
        }
        Instant now = clock.instant();
        if (orgWaiting) {
            orgMembership.admit(MembershipKind.MEMBER, null, now);
            activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_MEMBER_APPROVED", "ACCOUNT", accountId,
                    MembershipState.REQUESTED.name(), MembershipKind.MEMBER.name()));
        }
        if (request.inviteLinkId() == null) {
            request.admit(request.role(), null, now);
        }
        else {
            request.admitByLink(request.role(), request.inviteLinkId(), now);
        }
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_MEMBER_APPROVED", "ACCOUNT", accountId,
                MembershipState.REQUESTED.name(), request.role().name()));
    }

    @Transactional
    public void rejectTeam(UUID orgId, UUID teamId, UUID actorId, UUID accountId) {
        locks.activeTeam(orgId, teamId);
        requireTeamAdmin(orgId, teamId, actorId);
        TeamMembership request = teamRequest(teamId, accountId);
        Instant now = clock.instant();
        request.reject(now);
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_MEMBER_REJECTED", "ACCOUNT", accountId,
                MembershipState.REQUESTED.name(), MembershipState.REJECTED.name()));
        Optional<OrgMembership> orgMembership = orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .filter(membership -> membership.state() == MembershipState.REQUESTED
                        && !membership.admissionReview());
        boolean otherRequests = teamMemberships.findOpenByOrgIdAndAccountId(orgId, accountId).stream()
                .anyMatch(membership -> membership.state() == MembershipState.REQUESTED);
        if (orgMembership.isPresent() && !otherRequests) {
            orgMembership.get().rejectWithoutCooldown(now);
            activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_MEMBER_REJECTED", "ACCOUNT", accountId,
                    MembershipState.REQUESTED.name(), MembershipState.REJECTED.name()));
        }
    }

    private void place(UUID teamId, UUID accountId, UUID actorId, TeamRole role, Optional<TeamMembership> existing,
            Instant now) {
        String before = null;
        if (existing.isPresent()) {
            TeamMembership membership = existing.get();
            if (membership.state() == MembershipState.ACTIVE && !membership.expired(now)) {
                return;
            }
            before = membership.expired(now) ? MembershipState.EXPIRED.name() : membership.state().name();
            membership.admit(role, null, now);
        }
        else {
            teamMemberships.save(TeamMembership.active(Uuids.v7(), teamId, accountId, role, JoinedVia.DIRECT, now));
        }
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_MEMBER_ADDED", "ACCOUNT", accountId, before,
                role.name()));
    }

    private void requireOrgAdmin(UUID orgId, UUID actorId) {
        OrgMembership actor = orgMemberships.findByOrgIdAndAccountId(orgId, actorId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE)
                .orElseThrow(JoinRequestReviewService::notFound);
        if (!actor.orgAdmin()) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
    }

    private void requireTeamAdmin(UUID orgId, UUID teamId, UUID actorId) {
        OrgMembership actor = orgMemberships.findByOrgIdAndAccountId(orgId, actorId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE)
                .orElseThrow(JoinRequestReviewService::notFound);
        boolean orgAdmin = actor.orgAdmin() && actor.kind() == MembershipKind.MEMBER;
        boolean teamAdmin = teamMemberships.findByTeamIdAndAccountId(teamId, actorId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE
                        && membership.role() == TeamRole.TEAM_ADMIN)
                .isPresent();
        if (!orgAdmin && !teamAdmin) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
    }

    private OrgMembership orgRequest(UUID orgId, UUID accountId) {
        OrgMembership membership = orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .orElseThrow(JoinRequestReviewService::notFound);
        if (membership.state() == MembershipState.REQUESTED && !membership.admissionReview()) {
            throw notFound();
        }
        if (membership.state() != MembershipState.REQUESTED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        return membership;
    }

    private TeamMembership teamRequest(UUID teamId, UUID accountId) {
        TeamMembership membership = teamMemberships.findByTeamIdAndAccountId(teamId, accountId)
                .orElseThrow(JoinRequestReviewService::notFound);
        if (membership.state() != MembershipState.REQUESTED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        return membership;
    }

    private Map<UUID, List<RequestedTeam>> requestedTeams(UUID orgId, List<UUID> accountIds) {
        Map<UUID, List<RequestedTeam>> result = new LinkedHashMap<>();
        if (accountIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT tm.account_id, tm.team_id, t.name, tm.role
                        FROM team_membership tm
                        JOIN team t ON t.id = tm.team_id
                        WHERE t.org_id = :org AND tm.account_id IN (:accounts) AND tm.state = 'REQUESTED'
                        ORDER BY t.name, t.id
                        """)
                .param("org", orgId.toString())
                .param("accounts", accountIds.stream().map(UUID::toString).toList())
                .query((rs, rowNum) -> Map.entry(UUID.fromString(rs.getString("account_id")),
                        new RequestedTeam(UUID.fromString(rs.getString("team_id")), rs.getString("name"),
                                TeamRole.valueOf(rs.getString("role")))))
                .list()
                .forEach(entry -> result.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
        return result;
    }

    private <T> CursorPage<T> page(String scope, List<RequestRow> rows, List<RequestRow> page, int limit,
            List<T> items) {
        if (rows.size() <= limit) {
            return new CursorPage<>(items, null);
        }
        RequestRow last = page.get(limit - 1);
        return new CursorPage<>(items, cursors.encode(scope, List.of(last.requestedAt().toString(), last.id())));
    }

    private static String name(Map<UUID, AccountProfile> profiles, UUID accountId) {
        AccountProfile profile = profiles.get(accountId);
        return profile == null ? null : profile.displayName();
    }

    private static ReberuException invalid(String field, FieldReason reason) {
        return new ReberuException(ErrorCode.INVALID_REQUEST, List.of(new FieldViolation(field, reason)));
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    private record RequestRow(String id, UUID accountId, JoinedVia joinedVia, TeamRole role,
            boolean orgReviewPending, LocalDateTime requestedAt) {
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

    public record RequestedTeam(UUID teamId, String name, TeamRole role) {
    }

    public record OrgRequestView(UUID accountId, String displayName, JoinedVia joinedVia, Instant requestedAt,
            List<RequestedTeam> teams) {
    }

    public record TeamRequestView(UUID accountId, String displayName, TeamRole role, JoinedVia joinedVia,
            Instant requestedAt, boolean orgReviewPending) {
    }

}
