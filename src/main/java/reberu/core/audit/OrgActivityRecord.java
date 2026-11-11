package reberu.core.audit;

import java.time.Instant;
import java.util.UUID;

public record OrgActivityRecord(UUID id, UUID teamId, Instant occurredAt, UUID actorId, String action,
        String targetType, UUID targetId, String beforeValue, String afterValue) {
}
