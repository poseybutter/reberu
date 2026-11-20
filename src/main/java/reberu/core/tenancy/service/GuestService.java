package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;

public class GuestService {

    private final StateLocks locks;
    private final OrgMembershipRepository orgMemberships;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final ActivityLogs activityLogs;
    private final Clock clock;

    GuestService(StateLocks locks, OrgMembershipRepository orgMemberships, TeamRepository teams,
            TeamMembershipRepository teamMemberships, ActivityLogs activityLogs, Clock clock) {
        this.locks = locks;
        this.orgMemberships = orgMemberships;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.activityLogs = activityLogs;
        this.clock = clock;
    }

    @Transactional
    public GuestPeriod extend(UUID orgId, UUID actorId, UUID accountId, UUID teamId, int days) {
        Organization organization = locks.activeOrg(orgId);
        requireOrgAdmin(orgId, actorId);
        OrgMembership target = findGuest(orgId, accountId);
        Team team = teams.findForUpdate(orgId, teamId).orElseThrow(GuestService::notFound);
        TeamMembership membership = teamMemberships.findByTeamIdAndAccountId(teamId, accountId)
                .filter(found -> found.current() || found.state() == MembershipState.EXPIRED)
                .orElseThrow(GuestService::notFound);
        if (!target.renewableGuest() || !membership.renewableGuest()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST);
        }
        if (team.state() != TeamState.ACTIVE) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        if (days < 1 || days > organization.guestMaxDays()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST,
                    List.of(new FieldViolation("days", FieldReason.NOT_ALLOWED_VALUE)));
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.MICROS);
        if (!membership.expiresAt().isBefore(expiresAt)) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        String before = membership.expiresAt().toString();
        boolean revived = target.state() == MembershipState.EXPIRED;
        membership.renewGuest(expiresAt, now);
        target.renewGuest(expiresAt, now);
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "GUEST_EXTENDED", "ACCOUNT", accountId, before,
                expiresAt.toString()));
        if (revived) {
            activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "GUEST_EXTENDED", "ACCOUNT", accountId,
                    MembershipState.EXPIRED.name(), MembershipState.ACTIVE.name()));
        }
        return new GuestPeriod(membership.expiresAt(), target.expiresAt());
    }

    @Transactional
    public void convert(UUID orgId, UUID actorId, UUID accountId) {
        locks.activeOrg(orgId);
        requireOrgAdmin(orgId, actorId);
        OrgMembership target = findGuest(orgId, accountId);
        if (target.kind() == MembershipKind.MEMBER) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        Instant now = clock.instant();
        if (!target.current() || (target.expiresAt() != null && !now.isBefore(target.expiresAt()))) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST);
        }
        for (TeamMembership membership : teamMemberships.findOpenByOrgIdAndAccountId(orgId, accountId)) {
            if (!membership.current() || membership.expiresAt() == null) {
                continue;
            }
            if (membership.pastExpiry(now)) {
                String before = membership.state().name();
                membership.expire(now);
                activityLogs.recordTeam(membership.teamId(), new ActivityEntry(null, "GUEST_EXPIRED", "ACCOUNT",
                        accountId, before, MembershipState.EXPIRED.name()));
            }
            else {
                membership.clearExpiry(now);
            }
        }
        target.convertToMember(now);
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "GUEST_CONVERTED", "ACCOUNT", accountId,
                MembershipKind.GUEST.name(), MembershipKind.MEMBER.name()));
    }

    private void requireOrgAdmin(UUID orgId, UUID actorId) {
        OrgMembership actor = orgMemberships.findByOrgIdAndAccountId(orgId, actorId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE)
                .orElseThrow(GuestService::notFound);
        if (!actor.orgAdmin()) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
    }

    private OrgMembership findGuest(UUID orgId, UUID accountId) {
        return orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .filter(membership -> membership.current() || membership.state() == MembershipState.EXPIRED)
                .orElseThrow(GuestService::notFound);
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    public record GuestPeriod(Instant teamExpiresAt, Instant orgExpiresAt) {
    }

}
