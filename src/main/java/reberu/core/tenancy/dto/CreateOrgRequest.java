package reberu.core.tenancy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.service.OrgCreationService.NewOrg;

public record CreateOrgRequest(
        @NotNull @Size(max = Organization.NAME_MAX) String name,
        @Size(max = Organization.TAGLINE_MAX) String tagline,
        @NotNull Visibility visibility,
        @NotNull @Size(max = Organization.TIME_ZONE_MAX) String timeZone,
        @NotNull @Valid FirstTeam firstTeam) {

    public NewOrg toNewOrg() {
        return new NewOrg(name, tagline, visibility, timeZone, firstTeam.name(), firstTeam.jobTypeKey());
    }

    public record FirstTeam(
            @NotNull @Size(max = Team.NAME_MAX) String name,
            @NotNull @Size(max = 40) String jobTypeKey) {
    }

}
