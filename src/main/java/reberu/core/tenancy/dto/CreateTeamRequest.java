package reberu.core.tenancy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.service.TeamService.NewTeam;

public record CreateTeamRequest(
        @NotNull @Size(max = Team.NAME_MAX) String name,
        @NotNull Visibility visibility,
        @NotNull UUID jobTypeId,
        @NotNull UUID firstAdminAccountId) {

    public NewTeam toNewTeam() {
        return new NewTeam(name, visibility, jobTypeId, firstAdminAccountId);
    }

}
