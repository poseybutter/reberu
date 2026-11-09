package reberu.core.tenancy;

import java.util.UUID;

public interface OrgBootstrap {

    boolean grantFirstAdmin(UUID accountId, String email);

}
