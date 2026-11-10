package reberu.core.tenancy.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;

public interface TeamMembershipRepository extends Repository<TeamMembership, UUID> {

    TeamMembership save(TeamMembership membership);

    Optional<TeamMembership> findByTeamIdAndAccountId(UUID teamId, UUID accountId);

    long countByTeamIdAndRoleAndState(UUID teamId, TeamRole role, MembershipState state);

}
