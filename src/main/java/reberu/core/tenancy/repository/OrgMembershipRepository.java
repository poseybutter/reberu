package reberu.core.tenancy.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;

public interface OrgMembershipRepository extends JpaRepository<OrgMembership, UUID> {

    Optional<OrgMembership> findByOrgIdAndAccountId(UUID orgId, UUID accountId);

    long countByOrgIdAndOrgAdminTrueAndState(UUID orgId, MembershipState state);

    long countByOrgIdAndOrgAdminTrueAndStateAndAccountIdNot(UUID orgId, MembershipState state, UUID accountId);

    List<OrgMembership> findByOrgIdAndStateOrderByCreatedAtAscIdAsc(UUID orgId, MembershipState state);

    long countByOrgIdAndState(UUID orgId, MembershipState state);

}
