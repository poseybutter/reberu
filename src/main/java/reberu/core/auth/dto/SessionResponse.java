package reberu.core.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResponse(State state, UUID accountId, Instant authenticatedAt) {

    public enum State {
        ANONYMOUS,
        SIGNUP_PENDING,
        AUTHENTICATED
    }

    public static SessionResponse anonymous() {
        return new SessionResponse(State.ANONYMOUS, null, null);
    }

    public static SessionResponse signupPending() {
        return new SessionResponse(State.SIGNUP_PENDING, null, null);
    }

    public static SessionResponse authenticated(UUID accountId, Instant authenticatedAt) {
        return new SessionResponse(State.AUTHENTICATED, accountId, authenticatedAt);
    }

}
