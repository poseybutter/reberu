package reberu.core.access.web;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.MembershipDirectory;
import reberu.core.access.MembershipDirectory.OrgMember;
import reberu.core.access.MembershipDirectory.OrgView;
import reberu.core.access.MembershipDirectory.Role;
import reberu.core.access.MembershipDirectory.Status;
import reberu.core.access.MembershipDirectory.TeamMember;
import reberu.core.access.MembershipDirectory.TeamView;
import reberu.core.access.OrgContext;
import reberu.core.access.TeamContext;
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;

class AccessDecider {

    private final MembershipDirectory directory;
    private final Clock clock;

    AccessDecider(MembershipDirectory directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    OrgContext org(Access access, UUID accountId, String orgIdValue) {
        UUID orgId = parse(orgIdValue);
        Instant now = clock.instant();
        OrgView org = directory.org(orgId, accountId).orElseThrow(AccessDecider::notFound);
        OrgMember member = visibleMember(org, now);
        if (member.guest()) {
            throw notFound();
        }
        if (access.write() && org.status() != Status.ACTIVE) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        if (access.value() == AccessLevel.ORG_ADMIN && !member.orgAdmin()) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        return new OrgContext(orgId, accountId, member.orgAdmin());
    }

    TeamContext team(Access access, UUID accountId, String teamIdValue) {
        UUID teamId = parse(teamIdValue);
        Instant now = clock.instant();
        TeamView team = directory.team(teamId, accountId).orElseThrow(AccessDecider::notFound);
        OrgMember orgMember = visibleMember(team.org(), now);
        TeamMember teamMember = team.member();
        boolean member = teamMember != null && teamMember.active() && notExpired(teamMember.expiresAt(), now);
        boolean orgAdmin = orgMember.orgAdmin() && !orgMember.guest();
        if (!member && !orgAdmin) {
            throw notFound();
        }
        Role role = member ? teamMember.role() : null;
        if (access.write() && (team.status() != Status.ACTIVE || team.org().status() != Status.ACTIVE)) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        if (!allowed(access.value(), role, orgAdmin)) {
            throw new ReberuException(ErrorCode.FORBIDDEN_ACTION);
        }
        return new TeamContext(teamId, team.org().orgId(), accountId, role, orgAdmin, orgMember.guest());
    }

    private static OrgMember visibleMember(OrgView org, Instant now) {
        if (org.status() == Status.CLOSED) {
            throw notFound();
        }
        OrgMember member = org.member();
        if (member == null || !member.active() || (member.guest() && !notExpired(member.expiresAt(), now))) {
            throw notFound();
        }
        return member;
    }

    private static boolean allowed(AccessLevel level, Role role, boolean orgAdmin) {
        return switch (level) {
            case TEAM_VIEWER -> true;
            case TEAM_MEMBER -> orgAdmin || role == Role.TEAM_MEMBER || role == Role.TEAM_ADMIN;
            case TEAM_ADMIN -> orgAdmin || role == Role.TEAM_ADMIN;
            default -> throw new IllegalStateException("팀 권한 수준 아님: " + level);
        };
    }

    private static boolean notExpired(Instant expiresAt, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    private static UUID parse(String value) {
        if (value == null || value.length() != 36) {
            throw notFound();
        }
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) {
                throw notFound();
            }
            return id;
        }
        catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

}
