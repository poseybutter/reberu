package reberu.core.module;

import java.util.List;
import java.util.UUID;
import reberu.core.event.TeamScoped;

public record TeamModuleChanged(UUID orgId, UUID teamId, List<String> enabledModules) implements TeamScoped {

    public TeamModuleChanged {
        enabledModules = List.copyOf(enabledModules);
    }

}
