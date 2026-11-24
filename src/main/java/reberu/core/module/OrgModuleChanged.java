package reberu.core.module;

import java.util.List;
import java.util.UUID;
import reberu.core.event.OrgScoped;

public record OrgModuleChanged(UUID orgId, List<String> enabledModules) implements OrgScoped {

    public OrgModuleChanged {
        enabledModules = List.copyOf(enabledModules);
    }

}
