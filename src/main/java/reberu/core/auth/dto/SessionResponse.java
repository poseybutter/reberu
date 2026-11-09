package reberu.core.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import reberu.core.tenancy.DeploymentMode;
import reberu.core.tenancy.HomeScreens.HomeNext;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResponse(State state, DeploymentMode deploymentMode, UUID accountId, Instant authenticatedAt,
        HomeNext next) {

    public enum State {
        ANONYMOUS,
        SIGNUP_PENDING,
        AUTHENTICATED
    }

    public static SessionResponse anonymous(DeploymentMode mode) {
        return new SessionResponse(State.ANONYMOUS, mode, null, null, null);
    }

    public static SessionResponse signupPending(DeploymentMode mode) {
        return new SessionResponse(State.SIGNUP_PENDING, mode, null, null, null);
    }

    public static SessionResponse authenticated(DeploymentMode mode, UUID accountId, Instant authenticatedAt,
            HomeNext next) {
        return new SessionResponse(State.AUTHENTICATED, mode, accountId, authenticatedAt, next);
    }

}
