package reberu.core.access;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MembershipDirectory {

    Optional<OrgView> org(UUID orgId, UUID accountId);

    Optional<TeamView> team(UUID teamId, UUID accountId);

    enum Status {
        ACTIVE,
        READ_ONLY,
        CLOSED
    }

    enum Role {
        TEAM_ADMIN,
        TEAM_MEMBER,
        VIEWER
    }

    record OrgMember(boolean active, boolean guest, boolean orgAdmin, Instant expiresAt) {
    }

    record OrgView(UUID orgId, Status status, OrgMember member) {
    }

    record TeamMember(boolean active, Role role, Instant expiresAt) {
    }

    record TeamView(UUID teamId, Status status, OrgView org, TeamMember member) {
    }

}
