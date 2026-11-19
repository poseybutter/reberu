package reberu.core.tenancy.dto;

import java.util.UUID;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;

public final class JoinRequestRequests {

    private JoinRequestRequests() {
    }

    public record Approve(UUID teamId, TeamRole role) {
    }

}
