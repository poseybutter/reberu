package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import reberu.core.support.EmailAddresses;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimits;
import reberu.core.support.ReberuException;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.Invitation;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TenancyCodes.InvitationState;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.repository.InvitationRepository;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;

public class InvitationService {

    public static final int EMAILS_MAX = 50;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final Set<TeamRole> INVITABLE_ROLES = Set.of(TeamRole.TEAM_MEMBER, TeamRole.VIEWER);
    private static final List<InvitationState> OPEN_STATES = List.of(InvitationState.PENDING_APPROVAL,
            InvitationState.PENDING);

    private final StateLocks locks;
    private final InvitationRepository invitations;
    private final OrgMembershipRepository orgMemberships;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final Accounts accounts;
    private final ActivityLogs activityLogs;
    private final RateLimits rateLimits;
    private final CursorCodec cursors;
    private final JdbcClient jdbc;
    private final Clock clock;

    InvitationService(StateLocks locks, InvitationRepository invitations, OrgMembershipRepository orgMemberships,
            TeamRepository teams, TeamMembershipRepository teamMemberships, Accounts accounts,
            ActivityLogs activityLogs, RateLimits rateLimits, CursorCodec cursors, JdbcClient jdbc, Clock clock) {
        this.locks = locks;
        this.invitations = invitations;
        this.orgMemberships = orgMemberships;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.accounts = accounts;
        this.activityLogs = activityLogs;
        this.rateLimits = rateLimits;
        this.cursors = cursors;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public List<Created> createForOrg(UUID orgId, UUID actorId, UUID teamId, NewInvitations input) {
        List<String> emails = validate(input, teamId != null);
        countEmails(actorId, emails.size());
        Organization organization = locks.activeOrg(orgId);
        if (teamId != null) {
            Team team = teams.findForUpdate(orgId, teamId).orElse(null);
            if (team == null || team.state() != TeamState.ACTIVE) {
                throw invalid("teamId", FieldReason.NOT_ALLOWED_VALUE);
            }
        }
        if (!orgAdmin(orgId, actorId)) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        return create(organization, teamId, actorId, true, emails, input);
    }

    @Transactional
    public List<Created> createForTeam(UUID orgId, UUID teamId, UUID actorId, NewInvitations input) {
        List<String> emails = validate(input, true);
        countEmails(actorId, emails.size());
        locks.activeTeam(orgId, teamId);
        Organization organization = locks.activeOrg(orgId);
        boolean orgAdmin = orgAdmin(orgId, actorId);
        if (!orgAdmin && !teamAdmin(teamId, actorId)) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        return create(organization, teamId, actorId, orgAdmin, emails, input);
    }

    @Transactional(readOnly = true)
    public CursorPage<InvitationView> listForOrg(UUID orgId, InvitationFilter filter, String cursor, int limit) {
        return list("invitations:" + orgId, orgId, filter, cursor, limit);
    }

    @Transactional(readOnly = true)
    public CursorPage<InvitationView> listForTeam(UUID orgId, UUID teamId, InvitationState state,
            MembershipKind kind, String cursor, int limit) {
        return list("team-invitations:" + teamId, orgId, new InvitationFilter(state, kind, teamId), cursor, limit);
    }

    @Transactional
    public void revokeForOrg(UUID orgId, UUID actorId, UUID invitationId) {
        locks.activeOrg(orgId);
        if (!orgAdmin(orgId, actorId)) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        revoke(invitations.findForUpdate(orgId, invitationId).orElseThrow(InvitationService::notFound), actorId);
    }

    @Transactional
    public void revokeForTeam(UUID orgId, UUID teamId, UUID actorId, UUID invitationId) {
        locks.activeTeam(orgId, teamId);
        if (!orgAdmin(orgId, actorId) && !teamAdmin(teamId, actorId)) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        Invitation invitation = invitations.findForUpdate(orgId, invitationId)
                .filter(found -> teamId.equals(found.teamId()))
                .orElseThrow(InvitationService::notFound);
        revoke(invitation, actorId);
    }

    @Transactional
    public void approve(UUID orgId, UUID actorId, UUID invitationId) {
        Organization organization = locks.activeOrg(orgId);
        Invitation invitation = reviewable(orgId, actorId, invitationId);
        if (invitation.teamId() != null) {
            teams.findForUpdate(orgId, invitation.teamId())
                    .filter(team -> team.state() == TeamState.ACTIVE)
                    .orElseThrow(() -> new ReberuException(ErrorCode.READ_ONLY));
        }
        Instant now = clock.instant();
        invitation.approve(actorId, now.plus(Duration.ofDays(organization.invitationTtlDays())), now);
        record(invitation, new ActivityEntry(actorId, "INVITATION_APPROVED", "INVITATION", invitation.id(),
                InvitationState.PENDING_APPROVAL.name(), InvitationState.PENDING.name()));
    }

    @Transactional
    public void reject(UUID orgId, UUID actorId, UUID invitationId) {
        locks.activeOrg(orgId);
        Invitation invitation = reviewable(orgId, actorId, invitationId);
        invitation.revoke(clock.instant());
        record(invitation, new ActivityEntry(actorId, "INVITATION_REJECTED", "INVITATION", invitation.id(),
                InvitationState.PENDING_APPROVAL.name(), InvitationState.REVOKED.name()));
    }

    private List<Created> create(Organization organization, UUID teamId, UUID actorId, boolean orgAdmin,
            List<String> emails, NewInvitations input) {
        Integer guestDays = null;
        if (input.kind() == MembershipKind.GUEST) {
            guestDays = input.guestDays() == null ? organization.guestDefaultDays() : input.guestDays();
            if (guestDays < 1 || guestDays > organization.guestMaxDays()) {
                throw invalid("guestDays", FieldReason.NOT_ALLOWED_VALUE);
            }
        }
        Set<String> members = orgAdmin || input.kind() == MembershipKind.GUEST
                ? Set.of() : activeMembers(organization.id(), emails);
        Instant now = clock.instant();
        Instant acceptBy = now.plus(Duration.ofDays(organization.invitationTtlDays()));
        List<Invitation> existing = teamId == null
                ? invitations.findByOrgIdAndTeamIdIsNullAndEmailInAndStateIn(organization.id(), emails, OPEN_STATES)
                : invitations.findByOrgIdAndTeamIdAndEmailInAndStateIn(organization.id(), teamId, emails,
                        OPEN_STATES);
        for (Invitation replaced : existing) {
            String before = replaced.state().name();
            replaced.revoke(now);
            record(replaced, new ActivityEntry(actorId, "INVITATION_REVOKED", "INVITATION", replaced.id(), before,
                    InvitationState.REVOKED.name()));
        }
        TeamRole role = teamId == null ? null : input.role();
        List<Created> created = new ArrayList<>();
        for (String email : emails) {
            InvitationState state = needsApproval(organization, orgAdmin, input.kind(), members.contains(email))
                    ? InvitationState.PENDING_APPROVAL : InvitationState.PENDING;
            Invitation invitation = invitations.save(Invitation.create(Uuids.v7(), organization.id(), teamId,
                    email, input.kind(), role, guestDays, state, acceptBy, actorId, now));
            record(invitation, new ActivityEntry(actorId, "INVITATION_CREATED", "INVITATION", invitation.id(), null,
                    state.name()));
            created.add(new Created(invitation.id(), email, state, acceptBy));
        }
        return created;
    }

    private void countEmails(UUID actorId, int count) {
        if (count > 1) {
            rateLimits.acquire(RateLimit.INVITATION, RateLimits.accountKey(actorId), count - 1);
        }
    }

    private static boolean needsApproval(Organization organization, boolean orgAdmin, MembershipKind kind,
            boolean activeMember) {
        if (orgAdmin) {
            return false;
        }
        if (kind == MembershipKind.GUEST) {
            return organization.guestApprovalRequired();
        }
        return organization.memberAdmissionReview() && !activeMember;
    }

    private Set<String> activeMembers(UUID orgId, List<String> emails) {
        Map<String, Set<UUID>> ids = accounts.idsByEmail(emails);
        Set<UUID> all = new HashSet<>();
        ids.values().forEach(all::addAll);
        if (all.isEmpty()) {
            return Set.of();
        }
        Set<String> members = new HashSet<>(jdbc.sql("""
                        SELECT account_id FROM org_membership
                        WHERE org_id = :org AND account_id IN (:accounts) AND state = 'ACTIVE' AND kind = 'MEMBER'
                        """)
                .param("org", orgId.toString())
                .param("accounts", all.stream().map(UUID::toString).toList())
                .query(String.class)
                .list());
        Set<String> result = new HashSet<>();
        ids.forEach((email, accountIds) -> {
            if (accountIds.stream().allMatch(id -> members.contains(id.toString()))) {
                result.add(email);
            }
        });
        return result;
    }

    private static List<String> validate(NewInvitations input, boolean withTeam) {
        List<FieldViolation> violations = new ArrayList<>();
        List<String> emails = new ArrayList<>();
        if (input.emails() == null || input.emails().isEmpty()) {
            violations.add(new FieldViolation("emails", FieldReason.REQUIRED));
        }
        else if (input.emails().size() > EMAILS_MAX) {
            violations.add(new FieldViolation("emails", FieldReason.TOO_LONG));
        }
        else {
            Set<String> unique = new LinkedHashSet<>();
            for (String raw : input.emails()) {
                String email = raw == null ? "" : EmailAddresses.normalize(raw);
                if (!EmailAddresses.valid(email)) {
                    violations.add(new FieldViolation("emails", FieldReason.INVALID_FORMAT));
                    unique.clear();
                    break;
                }
                unique.add(email);
            }
            emails.addAll(unique);
        }
        if (input.kind() == null) {
            violations.add(new FieldViolation("kind", FieldReason.REQUIRED));
        }
        else if (input.kind() == MembershipKind.GUEST && !withTeam) {
            violations.add(new FieldViolation("teamId", FieldReason.REQUIRED));
        }
        if (withTeam) {
            if (input.role() == null) {
                violations.add(new FieldViolation("role", FieldReason.REQUIRED));
            }
            else if (!INVITABLE_ROLES.contains(input.role())) {
                violations.add(new FieldViolation("role", FieldReason.NOT_ALLOWED_VALUE));
            }
        }
        else if (input.role() != null && input.kind() != MembershipKind.GUEST) {
            violations.add(new FieldViolation("role", FieldReason.NOT_ALLOWED_VALUE));
        }
        if (input.kind() != MembershipKind.GUEST && input.guestDays() != null) {
            violations.add(new FieldViolation("guestDays", FieldReason.NOT_ALLOWED_VALUE));
        }
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        return emails;
    }

    private Invitation reviewable(UUID orgId, UUID actorId, UUID invitationId) {
        if (!orgAdmin(orgId, actorId)) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        Invitation invitation = invitations.findForUpdate(orgId, invitationId)
                .orElseThrow(InvitationService::notFound);
        if (invitation.state() != InvitationState.PENDING_APPROVAL) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        return invitation;
    }

    private void revoke(Invitation invitation, UUID actorId) {
        if (!invitation.open(clock.instant())) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        String before = invitation.state().name();
        invitation.revoke(clock.instant());
        record(invitation, new ActivityEntry(actorId, "INVITATION_REVOKED", "INVITATION", invitation.id(), before,
                InvitationState.REVOKED.name()));
    }

    private void record(Invitation invitation, ActivityEntry entry) {
        if (invitation.teamId() == null) {
            activityLogs.recordOrg(invitation.orgId(), entry);
        }
        else {
            activityLogs.recordTeam(invitation.teamId(), entry);
        }
    }

    private boolean orgAdmin(UUID orgId, UUID actorId) {
        return orgMemberships.findByOrgIdAndAccountId(orgId, actorId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE && membership.orgAdmin()
                        && membership.kind() == MembershipKind.MEMBER)
                .isPresent();
    }

    private boolean teamAdmin(UUID teamId, UUID actorId) {
        return teamMemberships.findByTeamIdAndAccountId(teamId, actorId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE
                        && membership.role() == TeamRole.TEAM_ADMIN)
                .isPresent();
    }

    private CursorPage<InvitationView> list(String scope, UUID orgId, InvitationFilter filter, String cursor,
            int limit) {
        Position after = cursor == null ? null : Position.of(cursors.decode(scope, cursor));
        StringBuilder where = new StringBuilder("i.org_id = :org");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (filter.teamId() != null) {
            where.append(" AND i.team_id = :team");
        }
        if (filter.kind() != null) {
            where.append(" AND i.kind = :kind");
        }
        if (filter.state() == InvitationState.EXPIRED) {
            where.append(" AND (i.state = 'EXPIRED' OR (i.state = 'PENDING' AND i.accept_by <= :now))");
        }
        else if (filter.state() == InvitationState.PENDING) {
            where.append(" AND i.state = 'PENDING' AND i.accept_by > :now");
        }
        else if (filter.state() != null) {
            where.append(" AND i.state = :state");
        }
        if (after != null) {
            where.append(" AND (i.created_at < :afterAt OR (i.created_at = :afterAt AND i.id < :afterId))");
        }
        JdbcClient.StatementSpec query = jdbc.sql("""
                        SELECT i.id, i.team_id, t.name AS team_name, i.email, i.kind, i.role, i.guest_days,
                               CASE WHEN i.state = 'PENDING' AND i.accept_by <= :now THEN 'EXPIRED'
                                    ELSE i.state END AS shown_state,
                               i.accept_by, i.created_by, i.created_at
                        FROM invitation i
                        LEFT JOIN team t ON t.id = i.team_id
                        WHERE """ + " " + where + " ORDER BY i.created_at DESC, i.id DESC LIMIT :limit")
                .param("org", orgId.toString())
                .param("now", now)
                .param("limit", limit + 1);
        if (filter.teamId() != null) {
            query = query.param("team", filter.teamId().toString());
        }
        if (filter.kind() != null) {
            query = query.param("kind", filter.kind().name());
        }
        if (filter.state() != null && filter.state() != InvitationState.EXPIRED
                && filter.state() != InvitationState.PENDING) {
            query = query.param("state", filter.state().name());
        }
        if (after != null) {
            query = query.param("afterAt", after.at()).param("afterId", after.id());
        }
        List<Row> rows = query.query((rs, rowNum) -> {
            String teamId = rs.getString("team_id");
            String role = rs.getString("role");
            String createdBy = rs.getString("created_by");
            Integer guestDays = rs.getObject("guest_days") == null ? null : rs.getInt("guest_days");
            return new Row(rs.getString("id"), teamId == null ? null : UUID.fromString(teamId),
                    rs.getString("team_name"), rs.getString("email"), MembershipKind.valueOf(rs.getString("kind")),
                    role == null ? null : TeamRole.valueOf(role), guestDays,
                    InvitationState.valueOf(rs.getString("shown_state")),
                    rs.getObject("accept_by", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    createdBy == null ? null : UUID.fromString(createdBy),
                    rs.getObject("created_at", LocalDateTime.class));
        }).list();
        List<Row> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        Map<UUID, AccountProfile> profiles = accounts.profiles(creators(page));
        List<InvitationView> items = page.stream().map(row -> {
            AccountProfile creator = row.createdBy() == null ? null : profiles.get(row.createdBy());
            return new InvitationView(UUID.fromString(row.id()), row.email(), row.kind(), row.teamId(),
                    row.teamName(), row.role(), row.guestDays(), row.state(), row.acceptBy(), row.createdBy(),
                    creator == null ? null : creator.displayName(), row.createdAt().toInstant(ZoneOffset.UTC));
        }).toList();
        if (rows.size() <= limit) {
            return new CursorPage<>(items, null);
        }
        Row last = page.get(limit - 1);
        return new CursorPage<>(items, cursors.encode(scope, List.of(last.createdAt().toString(), last.id())));
    }

    private static Collection<UUID> creators(List<Row> rows) {
        Set<UUID> ids = new HashSet<>();
        for (Row row : rows) {
            if (row.createdBy() != null) {
                ids.add(row.createdBy());
            }
        }
        return ids;
    }

    private static ReberuException invalid(String field, FieldReason reason) {
        return new ReberuException(ErrorCode.INVALID_REQUEST, List.of(new FieldViolation(field, reason)));
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    private record Row(String id, UUID teamId, String teamName, String email, MembershipKind kind, TeamRole role,
            Integer guestDays, InvitationState state, Instant acceptBy, UUID createdBy, LocalDateTime createdAt) {
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

    public record NewInvitations(List<String> emails, MembershipKind kind, TeamRole role, Integer guestDays) {
    }

    public record InvitationFilter(InvitationState state, MembershipKind kind, UUID teamId) {
    }

    public record Created(UUID invitationId, String email, InvitationState state, Instant acceptBy) {
    }

    public record InvitationView(UUID invitationId, String email, MembershipKind kind, UUID teamId,
            String teamName, TeamRole role, Integer guestDays, InvitationState state, Instant acceptBy,
            UUID createdBy, String createdByName, Instant createdAt) {
    }

}
