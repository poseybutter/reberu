package reberu.core.tenancy.service;

import java.util.UUID;
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;

class OrgAccess {

    private final OrganizationRepository organizations;
    private final OrgMembershipRepository memberships;

    OrgAccess(OrganizationRepository organizations, OrgMembershipRepository memberships) {
        this.organizations = organizations;
        this.memberships = memberships;
    }

    Member requireMember(UUID orgId, UUID accountId) {
        Organization organization = organizations.findById(orgId)
                .filter(org -> org.state() != OrgState.SUSPENDED && org.state() != OrgState.DELETED)
                .orElseThrow(OrgAccess::notFound);
        OrgMembership membership = memberships.findByOrgIdAndAccountId(orgId, accountId)
                .filter(found -> found.state() == MembershipState.ACTIVE)
                .filter(found -> found.kind() == MembershipKind.MEMBER)
                .orElseThrow(OrgAccess::notFound);
        return new Member(organization, membership);
    }

    Member requireAdmin(UUID orgId, UUID accountId) {
        Member member = requireMember(orgId, accountId);
        if (!member.membership().orgAdmin()) {
            throw notFound();
        }
        return member;
    }

    static void requireWritable(Organization organization) {
        if (organization.state() != OrgState.ACTIVE) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
    }

    static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    record Member(Organization organization, OrgMembership membership) {
    }

}
