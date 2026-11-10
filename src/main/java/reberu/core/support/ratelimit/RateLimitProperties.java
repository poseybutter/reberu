package reberu.core.support.ratelimit;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import reberu.core.support.RateLimit;

@ConfigurationProperties("reberu.rate-limit")
record RateLimitProperties(int maxKeysPerRule, Map<RateLimit, Rule> rules) {

    RateLimitProperties {
        if (maxKeysPerRule < 1) {
            throw new IllegalStateException("요청 제한 키 상한 값 오류(1 이상 필요)");
        }
        if (rules == null) {
            rules = Map.of();
        }
        for (RateLimit rule : RateLimit.values()) {
            Rule value = rules.get(rule);
            if (value == null || value.limit() < 1 || value.window() == null
                    || value.window().compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalStateException("요청 제한 값 오류(" + rule.id() + ", 한도 1 이상·창 1초 이상 필요)");
            }
        }
        rules = new EnumMap<>(rules);
    }

    record Rule(int limit, Duration window) {
    }

}
