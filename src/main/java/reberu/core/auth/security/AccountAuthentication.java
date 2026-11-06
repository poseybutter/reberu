package reberu.core.auth.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;

public final class AccountAuthentication extends AbstractAuthenticationToken {

    private final UUID accountId;
    private final Instant authenticatedAt;

    public AccountAuthentication(UUID accountId, Instant authenticatedAt) {
        super(List.of());
        this.accountId = accountId;
        this.authenticatedAt = authenticatedAt;
        setAuthenticated(true);
    }

    public UUID accountId() {
        return accountId;
    }

    public Instant authenticatedAt() {
        return authenticatedAt;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return accountId;
    }

}
