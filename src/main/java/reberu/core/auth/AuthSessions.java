package reberu.core.auth;

import java.util.UUID;

public interface AuthSessions {

    int revokeAll(UUID accountId);

}
