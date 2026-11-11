package reberu.core.tenancy.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;

public final class TeamMemberRequests {

    private TeamMemberRequests() {
    }

    public record Add(@NotNull UUID accountId, @NotNull TeamRole role) {
    }

    public record ChangeRole(@NotNull TeamRole role, @NotNull Integer version) {
    }

    public record Added(UUID accountId) {
    }

}
