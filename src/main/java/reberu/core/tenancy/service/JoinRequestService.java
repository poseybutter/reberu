package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.JoinRequestMode;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.entity.TenancyCodes.TeamJoinMode;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;

public class JoinRequestService {

    public static final int SEARCH_MIN = 2;
    public static final int SEARCH_MAX = 40;
    public static final int SEARCH_LIMIT = 20;
    public static final int MY_REQUESTS_LIMIT = 50;

    private final StateLocks locks;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;
    private final OrgMembershipRepository orgMemberships;
    private final TeamMembershipRepository teamMemberships;
    private final ActivityLogs activityLogs;
    private final JdbcClient jdbc;
    private final Duration cooldown;
    private final Clock clock;

    JoinRequestService(StateLocks locks, OrganizationRepository organizations, TeamRepository teams,
            OrgMembershipRepository orgMemberships, TeamMembershipRepository teamMemberships,
            ActivityLogs activityLogs, JdbcClient jdbc, Duration cooldown, Clock clock) {
        this.locks = locks;
        this.organizations = organizations;
        this.teams = teams;
        this.orgMemberships = orgMemberships;
        this.teamMemberships = teamMemberships;
        this.activityLogs = activityLogs;
        this.jdbc = jdbc;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PublicOrg> search(String query) {
        String term = validateQuery(query);
        return jdbc.sql("""
                        SELECT id, name, tagline, join_request_mode
                        FROM organization
                        WHERE visibility = 'PUBLIC' AND state = 'ACTIVE' AND join_request_mode <> 'OFF'
                          AND LOWER(name) LIKE :pattern ESCAPE '!'
                        ORDER BY name, id
                        LIMIT :limit
                        """)
                .param("pattern", "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%")
                .param("limit", SEARCH_LIMIT)
                .query((rs, rowNum) -> new PublicOrg(UUID.fromString(rs.getString("id")), rs.getString("name"),
                        rs.getString("tagline"), JoinRequestMode.valueOf(rs.getString("join_request_mode"))))
                .list();
    }

    @Transactional
    public Outcome joinOrg(UUID accountId, UUID orgId) {
        Organization organization = organizations.findForUpdate(orgId)
                .filter(JoinRequestService::openForRequests)
                .orElseThrow(JoinRequestService::notFound);
        OrgMembership membership = orgMemberships.findByOrgIdAndAccountId(orgId, accountId).orElse(null);
        Instant now = clock.instant();
        if (membership != null) {
            switch (membership.state()) {
                case ACTIVE -> {
                    if (membership.kind() == MembershipKind.GUEST) {
                        throw notFound();
                    }
                    return Outcome.JOINED;
                }
                case SUSPENDED -> throw notFound();
                case REQUESTED -> {
                    return Outcome.REQUESTED;
                }
                default -> requireCooledDown(membership.coolingDown(cooldown, now));
            }
        }
        boolean requested = organization.joinRequestMode() == JoinRequestMode.APPROVAL
                || membership != null && membership.removed();
        String before = membership == null ? null : membership.state().name();
        if (membership == null) {
            orgMemberships.save(OrgMembership.requested(Uuids.v7(), orgId, accountId, requested, now));
        }
        else if (requested) {
            membership.request(true, now);
        }
        else {
            membership.admit(MembershipKind.MEMBER, null, now);
        }
        activityLogs.recordOrg(orgId, new ActivityEntry(accountId,
                requested ? "ORG_MEMBER_REQUESTED" : "ORG_MEMBER_JOINED", "ACCOUNT", accountId, before,
                requested ? MembershipState.REQUESTED.name() : MembershipKind.MEMBER.name()));
        return requested ? Outcome.REQUESTED : Outcome.JOINED;
    }

    @Transactional
    public Outcome joinTeam(UUID orgId, UUID teamId, UUID accountId) {
        locks.activeOrg(orgId);
        Team team = teams.findForUpdate(orgId, teamId)
                .filter(found -> found.visibility() == Visibility.PUBLIC && found.state() == TeamState.ACTIVE)
                .orElseThrow(JoinRequestService::notFound);
        orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE
                        && membership.kind() == MembershipKind.MEMBER)
                .orElseThrow(JoinRequestService::notFound);
        TeamMembership membership = teamMemberships.findByTeamIdAndAccountId(teamId, accountId).orElse(null);
        Instant now = clock.instant();
        if (membership != null) {
            if (membership.state() == MembershipState.ACTIVE && !membership.expired(now)) {
                return Outcome.JOINED;
            }
            if (membership.state() == MembershipState.SUSPENDED) {
                throw notFound();
            }
            if (membership.state() == MembershipState.REQUESTED) {
                return Outcome.REQUESTED;
            }
            requireCooledDown(membership.coolingDown(cooldown, now));
        }
        boolean requested = team.joinMode() == TeamJoinMode.APPROVAL || membership != null && membership.removed();
        String before = membership == null ? null
                : membership.expired(now) ? MembershipState.EXPIRED.name() : membership.state().name();
        if (membership == null) {
            teamMemberships.save(TeamMembership.requested(Uuids.v7(), teamId, accountId, TeamRole.TEAM_MEMBER,
                    requested, now));
        }
        else if (requested) {
            membership.request(TeamRole.TEAM_MEMBER, null, now);
        }
        else {
            membership.admit(TeamRole.TEAM_MEMBER, null, now);
        }
        activityLogs.recordTeam(teamId, new ActivityEntry(accountId,
                requested ? "TEAM_MEMBER_REQUESTED" : "TEAM_MEMBER_JOINED", "ACCOUNT", accountId, before,
                TeamRole.TEAM_MEMBER.name()));
        return requested ? Outcome.REQUESTED : Outcome.JOINED;
    }

    @Transactional(readOnly = true)
    public List<MyRequest> listMine(UUID accountId) {
        Instant now = clock.instant();
        String account = accountId.toString();
        LocalDateTime since = LocalDateTime.ofInstant(now.minus(cooldown), ZoneOffset.UTC);
        List<MyRequest> items = new ArrayList<>();
        items.addAll(jdbc.sql("""
                        SELECT m.org_id, o.name AS org_name, m.state, m.updated_at, m.rejected_at
                        FROM org_membership m
                        JOIN organization o ON o.id = m.org_id
                        WHERE m.account_id = :account AND o.state = 'ACTIVE'
                          AND (m.state = 'REQUESTED' OR (m.state = 'REJECTED' AND m.rejected_at > :since))
                        ORDER BY m.updated_at DESC, m.org_id DESC
                        LIMIT :limit
                        """)
                .param("account", account)
                .param("since", since)
                .param("limit", MY_REQUESTS_LIMIT)
                .query((rs, rowNum) -> request(Target.ORG, UUID.fromString(rs.getString("org_id")),
                        rs.getString("org_name"), null, null, MembershipState.valueOf(rs.getString("state")),
                        instant(rs.getObject("updated_at", LocalDateTime.class)),
                        instant(rs.getObject("rejected_at", LocalDateTime.class))))
                .list());
        items.addAll(jdbc.sql("""
                        SELECT t.org_id, o.name AS org_name, tm.team_id, t.name AS team_name, tm.state, tm.updated_at,
                               tm.rejected_at
                        FROM team_membership tm
                        JOIN team t ON t.id = tm.team_id
                        JOIN organization o ON o.id = t.org_id
                        WHERE tm.account_id = :account AND t.state = 'ACTIVE' AND o.state = 'ACTIVE'
                          AND (tm.state = 'REQUESTED' OR (tm.state = 'REJECTED' AND tm.rejected_at > :since))
                        ORDER BY tm.updated_at DESC, tm.team_id DESC
                        LIMIT :limit
                        """)
                .param("account", account)
                .param("since", since)
                .param("limit", MY_REQUESTS_LIMIT)
                .query((rs, rowNum) -> request(Target.TEAM, UUID.fromString(rs.getString("org_id")),
                        rs.getString("org_name"), UUID.fromString(rs.getString("team_id")), rs.getString("team_name"),
                        MembershipState.valueOf(rs.getString("state")),
                        instant(rs.getObject("updated_at", LocalDateTime.class)),
                        instant(rs.getObject("rejected_at", LocalDateTime.class))))
                .list());
        return items.stream()
                .sorted(Comparator.comparing(MyRequest::sortedAt).reversed())
                .limit(MY_REQUESTS_LIMIT)
                .toList();
    }

    @Transactional
    public void cancelOrg(UUID accountId, UUID orgId) {
        organizations.findForUpdate(orgId).orElseThrow(JoinRequestService::notFound);
        OrgMembership membership = orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .orElseThrow(JoinRequestService::notFound);
        if (membership.state() != MembershipState.REQUESTED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        Instant now = clock.instant();
        membership.cancel(now);
        activityLogs.recordOrg(orgId, new ActivityEntry(accountId, "ORG_MEMBER_REQUEST_CANCELLED", "ACCOUNT",
                accountId, "REQUESTED", "LEFT"));
        for (TeamMembership request : teamMemberships.findOpenByOrgIdAndAccountId(orgId, accountId)) {
            if (request.state() == MembershipState.REQUESTED) {
                cancel(request, accountId, now);
            }
        }
    }

    @Transactional
    public void cancelTeam(UUID accountId, UUID teamId) {
        UUID orgId = jdbc.sql("SELECT org_id FROM team WHERE id = :team")
                .param("team", teamId.toString())
                .query((rs, rowNum) -> UUID.fromString(rs.getString("org_id")))
                .optional()
                .orElseThrow(JoinRequestService::notFound);
        organizations.findForUpdate(orgId).orElseThrow(JoinRequestService::notFound);
        teams.findForUpdate(orgId, teamId).orElseThrow(JoinRequestService::notFound);
        TeamMembership membership = teamMemberships.findByTeamIdAndAccountId(teamId, accountId)
                .orElseThrow(JoinRequestService::notFound);
        if (membership.state() != MembershipState.REQUESTED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        cancel(membership, accountId, clock.instant());
    }

    private void cancel(TeamMembership request, UUID accountId, Instant now) {
        request.cancel(now);
        activityLogs.recordTeam(request.teamId(), new ActivityEntry(accountId, "TEAM_MEMBER_REQUEST_CANCELLED",
                "ACCOUNT", accountId, "REQUESTED", "LEFT"));
    }

    private MyRequest request(Target target, UUID orgId, String orgName, UUID teamId, String teamName,
            MembershipState state, Instant updatedAt, Instant rejectedAt) {
        if (state == MembershipState.REQUESTED) {
            return new MyRequest(target, orgId, orgName, teamId, teamName, state, updatedAt, null, null);
        }
        return new MyRequest(target, orgId, orgName, teamId, teamName, state, null, rejectedAt,
                rejectedAt.plus(cooldown));
    }

    private static boolean openForRequests(Organization organization) {
        return organization.visibility() == Visibility.PUBLIC && organization.state() == OrgState.ACTIVE
                && organization.joinRequestMode() != JoinRequestMode.OFF;
    }

    private static void requireCooledDown(boolean coolingDown) {
        if (coolingDown) {
            throw new ReberuException(ErrorCode.REQUEST_COOLDOWN);
        }
    }

    private static String validateQuery(String query) {
        String term = query == null ? "" : query.strip();
        FieldReason reason = null;
        if (term.isEmpty()) {
            reason = FieldReason.REQUIRED;
        }
        else if (term.codePointCount(0, term.length()) < SEARCH_MIN) {
            reason = FieldReason.TOO_SHORT;
        }
        else if (term.codePointCount(0, term.length()) > SEARCH_MAX) {
            reason = FieldReason.TOO_LONG;
        }
        if (reason != null) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, List.of(new FieldViolation("q", reason)));
        }
        return term;
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    public enum Outcome {
        JOINED,
        REQUESTED
    }

    public enum Target {
        ORG,
        TEAM
    }

    public record PublicOrg(UUID orgId, String name, String tagline, JoinRequestMode joinRequestMode) {
    }

    public record MyRequest(Target target, UUID orgId, String orgName, UUID teamId, String teamName,
            MembershipState state, Instant requestedAt, Instant rejectedAt, Instant retryAt) {

        Instant sortedAt() {
            return requestedAt != null ? requestedAt : rejectedAt;
        }

    }

}
