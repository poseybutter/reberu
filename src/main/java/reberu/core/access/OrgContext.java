package reberu.core.access;

import java.util.UUID;

public record OrgContext(UUID orgId, UUID accountId, boolean orgAdmin) {
}
