package reberu.core.tenancy.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;

public interface OrgMembershipRepository extends JpaRepository<OrgMembership, UUID> {

    Optional<OrgMembership> findByOrgIdAndAccountId(UUID orgId, UUID accountId);

    long countByOrgIdAndOrgAdminTrueAndState(UUID orgId, MembershipState state);

    long countByOrgIdAndOrgAdminTrueAndStateAndAccountIdNot(UUID orgId, MembershipState state, UUID accountId);

    List<OrgMembership> findByOrgIdAndStateOrderByCreatedAtAscIdAsc(UUID orgId, MembershipState state);

    long countByOrgIdAndStateAndAdmissionReviewTrue(UUID orgId, MembershipState state);

    @Query("""
            select m from OrgMembership m
            where m.orgId = :orgId
              and m.state = reberu.core.tenancy.entity.TenancyCodes.MembershipState.ACTIVE
              and m.kind = reberu.core.tenancy.entity.TenancyCodes.MembershipKind.GUEST
              and (m.expiresAt <= :now or not exists (
                select tm.id from TeamMembership tm
                where tm.accountId = m.accountId
                  and tm.state in (reberu.core.tenancy.entity.TenancyCodes.MembershipState.ACTIVE,
                                   reberu.core.tenancy.entity.TenancyCodes.MembershipState.SUSPENDED)
                  and tm.teamId in (select t.id from Team t where t.orgId = :orgId)))
            order by m.accountId
            """)
    List<OrgMembership> findDueGuestsByOrgId(UUID orgId, Instant now);

}
