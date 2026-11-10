package reberu.core.audit;

import java.time.Instant;
import java.util.UUID;

public interface ActivityLogs {

    void recordOrg(UUID orgId, ActivityEntry entry);

    void recordTeam(UUID teamId, ActivityEntry entry);

    boolean orgRecordedSince(UUID orgId, UUID actorId, String action, UUID targetId, Instant since);

}
