package reberu.core.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TeamActivitySearch(UUID teamId, Instant from, Instant to, UUID actorId, String action, Instant afterAt,
        UUID afterId, int limit) {

    public TeamActivitySearch {
        Objects.requireNonNull(teamId, "팀 ID 없음");
        if ((afterAt == null) != (afterId == null)) {
            throw new IllegalArgumentException("활동 기록 커서 위치 오류(시각과 ID를 함께)");
        }
    }

}
