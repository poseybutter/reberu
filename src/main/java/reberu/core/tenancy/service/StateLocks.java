package reberu.core.tenancy.service;

import java.util.UUID;
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamRepository;

class StateLocks {

    private final OrganizationRepository organizations;
    private final TeamRepository teams;

    StateLocks(OrganizationRepository organizations, TeamRepository teams) {
        this.organizations = organizations;
        this.teams = teams;
    }

    Organization activeOrg(UUID orgId) {
        Organization organization = organizations.findForUpdate(orgId).orElseThrow(StateLocks::notFound);
        if (organization.state() != OrgState.ACTIVE) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        return organization;
    }

    Team activeTeam(UUID orgId, UUID teamId) {
        Organization organization = organizations.findForUpdate(orgId).orElseThrow(StateLocks::notFound);
        Team team = teams.findForUpdate(orgId, teamId).orElseThrow(StateLocks::notFound);
        if (organization.state() != OrgState.ACTIVE || team.state() != TeamState.ACTIVE) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        return team;
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

}
