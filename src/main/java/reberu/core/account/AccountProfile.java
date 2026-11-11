package reberu.core.account;

import java.time.Instant;
import java.util.UUID;

public record AccountProfile(UUID id, AccountState state, String displayName, String email, Instant lastLoginAt) {
}
