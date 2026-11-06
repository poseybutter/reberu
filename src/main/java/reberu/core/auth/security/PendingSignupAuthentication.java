package reberu.core.auth.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import reberu.core.account.GoogleProfile;

public final class PendingSignupAuthentication extends AbstractAuthenticationToken {

    private final String sessionIdHash;
    private final GoogleProfile profile;

    public PendingSignupAuthentication(String sessionIdHash, GoogleProfile profile) {
        super(List.of());
        this.sessionIdHash = sessionIdHash;
        this.profile = profile;
        setAuthenticated(true);
    }

    public String sessionIdHash() {
        return sessionIdHash;
    }

    public GoogleProfile profile() {
        return profile;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return "pending-signup";
    }

}
