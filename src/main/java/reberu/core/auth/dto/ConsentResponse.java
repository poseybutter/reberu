package reberu.core.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record ConsentResponse(UUID accountId, Instant authenticatedAt, String next) {
}
