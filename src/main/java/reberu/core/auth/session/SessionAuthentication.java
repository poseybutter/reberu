package reberu.core.auth.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SessionAuthentication(UUID accountId, Instant authenticatedAt) {

    public SessionAuthentication {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(authenticatedAt);
    }

}
