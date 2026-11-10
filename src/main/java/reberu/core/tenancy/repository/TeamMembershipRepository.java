package reberu.core.tenancy.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;

public interface TeamMembershipRepository extends Repository<TeamMembership, UUID> {

    TeamMembership save(TeamMembership membership);

    Optional<TeamMembership> findByTeamIdAndAccountId(UUID teamId, UUID accountId);

    long countByTeamIdAndRoleAndState(UUID teamId, TeamRole role, MembershipState state);

    @Query("""
            select m from TeamMembership m
            where m.state = :state and m.teamId in (select t.id from Team t where t.orgId = :orgId)
            order by m.createdAt, m.id
            """)
    List<TeamMembership> findByOrgIdAndState(UUID orgId, MembershipState state);

}
