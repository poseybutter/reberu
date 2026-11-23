package reberu.core.module;

import java.util.List;
import java.util.UUID;

public record OrgModuleChanged(UUID orgId, List<String> enabledModules) {

    public OrgModuleChanged {
        enabledModules = List.copyOf(enabledModules);
    }

}
