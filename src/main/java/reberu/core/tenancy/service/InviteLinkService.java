package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.repository.InviteLinkRepository;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;

public class InviteLinkService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_EXPIRES_DAYS = 7;
    public static final int MAX_EXPIRES_DAYS = 30;
    public static final int MAX_USES_MAX = 1000;

    private static final Set<TeamRole> LINK_ROLES = Set.of(TeamRole.TEAM_MEMBER, TeamRole.VIEWER);

    private final StateLocks locks;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;
    private final InviteLinkRepository links;
    private final OrgMembershipRepository orgMemberships;
    private final TeamMembershipRepository teamMemberships;
    private final Accounts accounts;
    private final ActivityLogs activityLogs;
    private final CursorCodec cursors;
    private final JdbcClient jdbc;
    private final String joinUrl;
    private final Clock clock;

    InviteLinkService(StateLocks locks, OrganizationRepository organizations, TeamRepository teams,
            InviteLinkRepository links,
            OrgMembershipRepository orgMemberships, TeamMembershipRepository teamMemberships, Accounts accounts,
            ActivityLogs activityLogs, CursorCodec cursors, JdbcClient jdbc, String baseUrl, Clock clock) {
        this.locks = locks;
        this.organizations = organizations;
        this.teams = teams;
        this.links = links;
        this.orgMemberships = orgMemberships;
        this.teamMemberships = teamMemberships;
        this.accounts = accounts;
        this.activityLogs = activityLogs;
        this.cursors = cursors;
        this.jdbc = jdbc;
        this.joinUrl = baseUrl.replaceAll("/+$", "") + "/join#";
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public void requireLinksAllowed(UUID orgId) {
        requireLinksAllowed(organizations.findById(orgId).orElseThrow(InviteLinkService::notFound));
    }

    @Transactional(readOnly = true)
    public CursorPage<LinkView> list(UUID orgId, UUID teamId, String cursor, int limit) {
        requireLinksAllowed(organizations.findById(orgId).orElseThrow(InviteLinkService::notFound));
        String scope = "invite-links:" + teamId;
        Position after = cursor == null ? null : Position.of(cursors.decode(scope, cursor));
        String where = after == null ? ""
                : " AND (created_at < :afterAt OR (created_at = :afterAt AND id < :afterId))";
        JdbcClient.StatementSpec query = jdbc.sql("""
                        SELECT id, approval_required, role, expires_at, max_uses, use_count, enabled, created_by,
                               created_at
                        FROM invite_link
                        WHERE team_id = :team""" + where + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("team", teamId.toString())
                .param("limit", limit + 1);
        if (after != null) {
            query = query.param("afterAt", after.at()).param("afterId", after.id());
        }
        List<Row> rows = query.query((rs, rowNum) -> new Row(rs.getString("id"),
                rs.getBoolean("approval_required"), TeamRole.valueOf(rs.getString("role")),
                rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getInt("max_uses"),
                rs.getInt("use_count"), rs.getBoolean("enabled"), UUID.fromString(rs.getString("created_by")),
                rs.getObject("created_at", LocalDateTime.class))).list();
        List<Row> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        Set<UUID> creators = new HashSet<>();
        page.forEach(row -> creators.add(row.createdBy()));
        Map<UUID, AccountProfile> profiles = accounts.profiles(creators);
        List<LinkView> items = page.stream().map(row -> {
            AccountProfile creator = profiles.get(row.createdBy());
            return new LinkView(UUID.fromString(row.id()), row.approvalRequired(), row.role(), row.expiresAt(),
                    row.useCount(), row.maxUses(), row.enabled(), row.createdBy(),
                    creator == null ? null : creator.displayName(), row.createdAt().toInstant(ZoneOffset.UTC));
        }).toList();
        if (rows.size() <= limit) {
            return new CursorPage<>(items, null);
        }
        Row last = page.get(limit - 1);
        return new CursorPage<>(items, cursors.encode(scope, List.of(last.createdAt().toString(), last.id())));
    }

    @Transactional
    public Created create(UUID orgId, UUID teamId, UUID actorId, NewLink input) {
        requireLinksAllowed(organizations.findById(orgId).orElseThrow(InviteLinkService::notFound));
        int expiresInDays = validate(input);
        locks.activeTeam(orgId, teamId);
        requireLinksAllowed(locks.activeOrg(orgId));
        requireAdmin(orgId, teamId, actorId);
        Instant now = clock.instant();
        String token = InviteTokens.newToken();
        Instant expiresAt = now.plus(Duration.ofDays(expiresInDays));
        InviteLink link = links.save(InviteLink.create(Uuids.v7(), orgId, teamId, InviteTokens.hash(token),
                input.approvalRequired(), input.role(), expiresAt, input.maxUses(), actorId, now));
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "INVITE_LINK_CREATED", "INVITE_LINK", link.id(),
                null, input.role().name()));
        return new Created(link.id(), joinUrl + token, expiresAt);
    }

    @Transactional
    public void disable(UUID orgId, UUID teamId, UUID actorId, UUID linkId) {
        requireLinksAllowed(organizations.findById(orgId).orElseThrow(InviteLinkService::notFound));
        locks.activeTeam(orgId, teamId);
        requireLinksAllowed(locks.activeOrg(orgId));
        requireAdmin(orgId, teamId, actorId);
        InviteLink link = links.findForUpdate(teamId, linkId).orElseThrow(InviteLinkService::notFound);
        if (!link.enabled()) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        disable(link, actorId, clock.instant());
    }

    void disableForTeam(UUID teamId, UUID actorId, Instant at) {
        links.findEnabledForUpdateByTeamId(teamId).forEach(link -> disable(link, actorId, at));
    }

    void disableForOrg(UUID orgId, UUID actorId, Instant at) {
        links.findEnabledForUpdateByOrgId(orgId).forEach(link -> disable(link, actorId, at));
    }

    private void disable(InviteLink link, UUID actorId, Instant at) {
        link.disable(at);
        activityLogs.recordTeam(link.teamId(), new ActivityEntry(actorId, "INVITE_LINK_DISABLED", "INVITE_LINK",
                link.id(), "ENABLED", "DISABLED"));
    }

    @Transactional
    public Redemption redeem(UUID accountId, String token) {
        if (!InviteTokens.isToken(token)) {
            return Redemption.FAILED;
        }
        String hash = InviteTokens.hash(token);
        Scope scope = jdbc.sql("SELECT id, org_id, team_id, token_hash FROM invite_link WHERE token_hash = :hash")
                .param("hash", hash)
                .query((rs, rowNum) -> new Scope(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("org_id")), UUID.fromString(rs.getString("team_id")),
                        rs.getString("token_hash")))
                .optional()
                .filter(found -> InviteTokens.sameHash(found.tokenHash(), hash))
                .orElse(null);
        if (scope == null) {
            return Redemption.FAILED;
        }
        Organization organization = organizations.findForUpdate(scope.orgId()).orElse(null);
        Team team = teams.findForUpdate(scope.orgId(), scope.teamId()).orElse(null);
        InviteLink link = links.findForUpdate(scope.teamId(), scope.linkId()).orElse(null);
        Instant now = clock.instant();
        if (organization == null || team == null || link == null || !usable(organization, team, link, now)) {
            return Redemption.FAILED;
        }
        boolean creatorOrgAdmin = orgAdmin(organization.id(), link.createdBy());
        if (!creatorOrgAdmin && !teamAdmin(team.id(), link.createdBy())) {
            return Redemption.FAILED;
        }
        OrgMembership orgMembership = orgMemberships.findByOrgIdAndAccountId(organization.id(), accountId)
                .orElse(null);
        if (orgMembership != null && (orgMembership.state() == MembershipState.SUSPENDED
                || orgMembership.kind() == MembershipKind.GUEST && orgMembership.state() == MembershipState.ACTIVE)) {
            return Redemption.FAILED;
        }
        boolean orgLive = orgMembership != null && orgMembership.state() == MembershipState.ACTIVE;
        TeamMembership teamMembership = teamMemberships.findByTeamIdAndAccountId(team.id(), accountId).orElse(null);
        if (teamMembership != null && teamMembership.state() == MembershipState.SUSPENDED) {
            return Redemption.FAILED;
        }
        if (teamMembership != null && teamMembership.state() == MembershipState.ACTIVE && !teamMembership.expired(now)) {
            return orgLive ? Redemption.of(Outcome.ALREADY_MEMBER, organization, team) : Redemption.FAILED;
        }
        boolean orgRequested = !orgLive
                && (link.approvalRequired() || !creatorOrgAdmin && organization.memberAdmissionReview());
        boolean teamRequested = link.approvalRequired() || orgRequested;
        boolean orgWaiting = orgLive || orgMembership != null && orgMembership.state() == MembershipState.REQUESTED;
        if (teamRequested && orgWaiting && teamMembership != null
                && teamMembership.state() == MembershipState.REQUESTED) {
            return Redemption.of(Outcome.REQUESTED, organization, team);
        }
        if (links.incrementUse(team.id(), link.id(), now) != 1) {
            return Redemption.FAILED;
        }
        if (orgMembership == null) {
            orgMemberships.save(OrgMembership.linked(Uuids.v7(), organization.id(), accountId, orgRequested, now));
            recordOrg(organization.id(), accountId, null, orgRequested);
        }
        else if (!orgLive && !(orgRequested && orgMembership.state() == MembershipState.REQUESTED)) {
            String before = orgMembership.state().name();
            if (orgRequested) {
                orgMembership.request(now);
            }
            else {
                orgMembership.admit(MembershipKind.MEMBER, null, now);
            }
            recordOrg(organization.id(), accountId, before, orgRequested);
        }
        String teamBefore = teamMembership == null ? null : teamBefore(teamMembership, now);
        if (teamMembership == null) {
            teamMemberships.save(TeamMembership.linked(Uuids.v7(), team.id(), accountId, link.role(), teamRequested,
                    link.id(), now));
        }
        else if (teamRequested) {
            if (teamMembership.state() != MembershipState.REQUESTED) {
                teamMembership.request(link.role(), link.id(), now);
            }
        }
        else {
            teamMembership.admitByLink(link.role(), link.id(), now);
        }
        activityLogs.recordTeam(team.id(), new ActivityEntry(accountId,
                teamRequested ? "TEAM_MEMBER_REQUESTED" : "TEAM_MEMBER_JOINED", "ACCOUNT", accountId, teamBefore,
                link.role().name()));
        Outcome outcome = teamRequested ? Outcome.REQUESTED : Outcome.JOINED;
        activityLogs.recordTeam(team.id(), new ActivityEntry(accountId, "INVITE_LINK_USED", "INVITE_LINK", link.id(),
                null, outcome.name()));
        return Redemption.of(outcome, organization, team);
    }

    private static boolean usable(Organization organization, Team team, InviteLink link, Instant now) {
        return link.enabled() && now.isBefore(link.expiresAt()) && link.useCount() < link.maxUses()
                && organization.state() == OrgState.ACTIVE && organization.joinLinkEnabled()
                && team.state() == TeamState.ACTIVE;
    }

    private void recordOrg(UUID orgId, UUID accountId, String before, boolean requested) {
        activityLogs.recordOrg(orgId, new ActivityEntry(accountId,
                requested ? "ORG_MEMBER_REQUESTED" : "ORG_MEMBER_JOINED", "ACCOUNT", accountId, before,
                requested ? MembershipState.REQUESTED.name() : MembershipKind.MEMBER.name()));
    }

    private static String teamBefore(TeamMembership membership, Instant now) {
        return membership.expired(now) ? MembershipState.EXPIRED.name() : membership.state().name();
    }

    private void requireAdmin(UUID orgId, UUID teamId, UUID actorId) {
        if (!orgAdmin(orgId, actorId) && !teamAdmin(teamId, actorId)) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
    }

    private boolean orgAdmin(UUID orgId, UUID accountId) {
        return orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE && membership.orgAdmin()
                        && membership.kind() == MembershipKind.MEMBER)
                .isPresent();
    }

    private boolean teamAdmin(UUID teamId, UUID accountId) {
        return teamMemberships.findByTeamIdAndAccountId(teamId, accountId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE
                        && membership.role() == TeamRole.TEAM_ADMIN)
                .isPresent();
    }

    private static void requireLinksAllowed(Organization organization) {
        if (!organization.joinLinkEnabled()) {
            throw notFound();
        }
    }

    private static int validate(NewLink input) {
        List<FieldViolation> violations = new ArrayList<>();
        if (input.approvalRequired() == null) {
            violations.add(new FieldViolation("approvalRequired", FieldReason.REQUIRED));
        }
        if (input.role() == null) {
            violations.add(new FieldViolation("role", FieldReason.REQUIRED));
        }
        else if (!LINK_ROLES.contains(input.role())) {
            violations.add(new FieldViolation("role", FieldReason.NOT_ALLOWED_VALUE));
        }
        int expiresInDays = input.expiresInDays() == null ? DEFAULT_EXPIRES_DAYS : input.expiresInDays();
        if (expiresInDays < 1 || expiresInDays > MAX_EXPIRES_DAYS) {
            violations.add(new FieldViolation("expiresInDays", FieldReason.NOT_ALLOWED_VALUE));
        }
        if (input.maxUses() == null) {
            violations.add(new FieldViolation("maxUses", FieldReason.REQUIRED));
        }
        else if (input.maxUses() < 1 || input.maxUses() > MAX_USES_MAX) {
            violations.add(new FieldViolation("maxUses", FieldReason.NOT_ALLOWED_VALUE));
        }
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        return expiresInDays;
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    private record Row(String id, boolean approvalRequired, TeamRole role, Instant expiresAt, int maxUses,
            int useCount, boolean enabled, UUID createdBy, LocalDateTime createdAt) {
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

    private record Scope(UUID linkId, UUID orgId, UUID teamId, String tokenHash) {
    }

    public enum Outcome {
        JOINED,
        REQUESTED,
        ALREADY_MEMBER
    }

    public record Redemption(Outcome outcome, UUID teamId, String orgName, String teamName) {

        static final Redemption FAILED = new Redemption(null, null, null, null);

        static Redemption of(Outcome outcome, Organization organization, Team team) {
            return new Redemption(outcome, team.id(), organization.name(), team.name());
        }

        public boolean failed() {
            return outcome == null;
        }

    }

    public record NewLink(Boolean approvalRequired, TeamRole role, Integer expiresInDays, Integer maxUses) {
    }

    public record Created(UUID linkId, String url, Instant expiresAt) {
    }

    public record LinkView(UUID linkId, boolean approvalRequired, TeamRole role, Instant expiresAt, int useCount,
            int maxUses, boolean enabled, UUID createdBy, String createdByName, Instant createdAt) {
    }

}
