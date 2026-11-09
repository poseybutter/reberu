package reberu.core.tenancy.dto;

import java.util.UUID;

public record CreateOrgResponse(UUID orgId, UUID teamId) {
}
