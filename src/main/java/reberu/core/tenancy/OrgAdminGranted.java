package reberu.core.tenancy;

import java.util.UUID;

public record OrgAdminGranted(UUID orgId, UUID accountId) {
}
