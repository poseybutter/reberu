package reberu.core.access;

import java.util.Optional;
import java.util.UUID;
import reberu.core.access.AccessLevel.Scope;

public interface ModuleSwitch {

    Optional<Scope> installedScope(String moduleKey);

    Optional<String> owner(Class<?> type);

    boolean enabledInOrg(UUID orgId, String moduleKey);

    boolean enabledInTeam(UUID teamId, String moduleKey);

}
