package reberu.core.module.dto;

import jakarta.validation.constraints.NotNull;

public final class ModuleRequests {

    private ModuleRequests() {
    }

    public record SetAllowed(@NotNull Boolean allowed) {
    }

    public record SetOrgEnabled(@NotNull Boolean enabled) {
    }

    public record SetTeamEnabled(@NotNull Boolean enabled, Boolean confirmCascade) {
    }

}
