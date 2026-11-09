package reberu.core.tenancy.dto;

import java.util.UUID;

public record RestoreTeamRequest(UUID newAdminAccountId) {
}
