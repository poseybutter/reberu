package reberu.core.module;

import java.util.List;
import java.util.UUID;

public record TeamModuleChanged(UUID orgId, UUID teamId, List<String> enabledModules) {

    public TeamModuleChanged {
        enabledModules = List.copyOf(enabledModules);
    }

}
