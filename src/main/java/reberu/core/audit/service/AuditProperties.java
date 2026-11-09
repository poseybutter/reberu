package reberu.core.audit.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reberu.audit")
public record AuditProperties(Duration retention) {

    public static final Duration MIN_RETENTION = Duration.ofDays(365);

    public AuditProperties {
        if (retention == null || retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalStateException("활동 기록 보관 기간 값 오류(365일 이상 필요)");
        }
    }

}
