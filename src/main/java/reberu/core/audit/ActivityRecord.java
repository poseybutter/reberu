package reberu.core.audit;

import java.time.Instant;
import java.util.UUID;

public record ActivityRecord(UUID id, Instant occurredAt, UUID actorId, String action, String targetType,
        UUID targetId, String beforeValue, String afterValue) {
}
