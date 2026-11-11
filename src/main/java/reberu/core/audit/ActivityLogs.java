package reberu.core.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ActivityLogs {

    void recordOrg(UUID orgId, ActivityEntry entry);

    void recordTeam(UUID teamId, ActivityEntry entry);

    boolean orgRecordedSince(UUID orgId, UUID actorId, String action, UUID targetId, Instant since);

    List<ActivityRecord> recentOrg(UUID orgId, int limit);

    List<OrgActivityRecord> searchOrg(OrgActivitySearch search);

}
