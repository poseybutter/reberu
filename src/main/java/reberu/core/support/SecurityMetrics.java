package reberu.core.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.Locale;

public final class SecurityMetrics {

    public static final String REJECTIONS = "reberu.security.rejections";
    public static final String RATE_LIMITED = "reberu.security.rate.limited";
    public static final String KIND = "kind";
    public static final String RULE = "rule";

    private SecurityMetrics() {
    }

    public static void rejected(Rejection kind) {
        Metrics.counter(REJECTIONS, KIND, kind.tag()).increment();
    }

    public static void rateLimited(RateLimit rule) {
        Metrics.counter(RATE_LIMITED, RULE, rule.id()).increment();
    }

    public static void bindTo(MeterRegistry registry) {
        for (Rejection kind : Rejection.values()) {
            Counter.builder(REJECTIONS).tag(KIND, kind.tag()).register(registry);
        }
        for (RateLimit rule : RateLimit.values()) {
            Counter.builder(RATE_LIMITED).tag(RULE, rule.id()).register(registry);
        }
    }

    public enum Rejection {
        LOGIN_FAILED,
        CSRF_INVALID,
        ACCESS_FORBIDDEN,
        ACCESS_HIDDEN;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

}
