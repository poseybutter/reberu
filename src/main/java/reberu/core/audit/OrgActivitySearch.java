package reberu.core.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrgActivitySearch(UUID orgId, UUID orgTargetId, List<UUID> teamIds, Instant from, Instant to,
        UUID actorId, String action, Instant afterAt, UUID afterId, int limit) {

    public OrgActivitySearch {
        Objects.requireNonNull(orgId, "조직 ID 없음");
        teamIds = List.copyOf(teamIds);
        if ((afterAt == null) != (afterId == null)) {
            throw new IllegalArgumentException("활동 기록 커서 위치 오류(시각과 ID를 함께)");
        }
    }

}
