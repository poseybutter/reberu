package reberu.core.audit;

import java.util.UUID;

public interface ActivityLogs {

    void recordOrg(UUID orgId, ActivityEntry entry);

    void recordTeam(UUID teamId, ActivityEntry entry);

}
