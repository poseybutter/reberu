package reberu.core.tenancy;

import java.util.UUID;
import reberu.core.event.OrgScoped;

public record OrgAdminGranted(UUID orgId, UUID accountId) implements OrgScoped {
}
