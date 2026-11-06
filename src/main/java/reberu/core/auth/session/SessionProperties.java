package reberu.core.auth.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reberu.session")
public record SessionProperties(Duration idleTimeout, Duration maxLifetime, int maxPerAccount) {

    public SessionProperties {
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalStateException("세션 유휴 만료 값 오류(양수 필요)");
        }
        if (maxLifetime == null || maxLifetime.compareTo(idleTimeout) < 0) {
            throw new IllegalStateException("세션 최대 수명 값 오류(유휴 만료 이상 필요)");
        }
        if (maxPerAccount < 1) {
            throw new IllegalStateException("계정당 세션 상한 값 오류(1 이상 필요)");
        }
    }

}
