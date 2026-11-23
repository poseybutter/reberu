package reberu.core.module;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ModuleStates {

    void enableForNewTeam(UUID orgId, UUID teamId, List<String> jobTypeModules, UUID actorId, Instant at);

    List<String> enabledTeamModules(UUID teamId);

}
