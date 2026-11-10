package reberu.core.access;

import java.util.UUID;
import reberu.core.access.MembershipDirectory.Role;

public record TeamContext(UUID teamId, UUID orgId, UUID accountId, Role role, boolean orgAdmin, boolean guest) {

    public boolean viewingAsOrgAdmin() {
        return role == null && orgAdmin;
    }

}
