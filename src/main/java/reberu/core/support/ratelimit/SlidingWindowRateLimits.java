package reberu.core.support.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimitExceededException;
import reberu.core.support.RateLimits;

class SlidingWindowRateLimits implements RateLimits {

    static final String SECURITY_LOGGER = "reberu.security";

    private static final Logger securityLog = LoggerFactory.getLogger(SECURITY_LOGGER);

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<RateLimit, ConcurrentHashMap<String, Window>> windows = new EnumMap<>(RateLimit.class);

    SlidingWindowRateLimits(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        for (RateLimit rule : RateLimit.values()) {
            windows.put(rule, new ConcurrentHashMap<>());
        }
    }

    @Override
    public void acquire(RateLimit rule, String key) {
        RateLimitProperties.Rule config = properties.rules().get(rule);
        ConcurrentHashMap<String, Window> keys = windows.get(rule);
        long size = config.window().toMillis();
        long now = clock.millis();
        long index = Math.floorDiv(now, size);
        if (!keys.containsKey(key) && keys.size() >= properties.maxKeysPerRule()) {
            purge(rule, index);
            if (keys.size() >= properties.maxKeysPerRule()) {
                securityLog.warn("요청 제한 키 상한 도달: rule={}", rule.id());
                throw new RateLimitExceededException(rule, config.window());
            }
        }
        long elapsed = now - index * size;
        long[] wait = {0};
        boolean[] report = {false};
        keys.compute(key, (ignored, window) -> {
            Window current = window == null ? new Window(index) : window;
            current.advance(index);
            wait[0] = current.tryAcquire(config.limit(), size, elapsed);
            if (wait[0] > 0 && !current.reported) {
                current.reported = true;
                report[0] = true;
            }
            return current;
        });
        if (wait[0] > 0) {
            if (report[0]) {
                securityLog.warn("요청 제한 초과: rule={}, key={}", rule.id(), maskKey(key));
            }
            throw new RateLimitExceededException(rule, Duration.ofMillis(wait[0]));
        }
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    void purgeStale() {
        long now = clock.millis();
        for (RateLimit rule : RateLimit.values()) {
            purge(rule, Math.floorDiv(now, properties.rules().get(rule).window().toMillis()));
        }
    }

    int size(RateLimit rule) {
        return windows.get(rule).size();
    }

    private void purge(RateLimit rule, long index) {
        windows.get(rule).values().removeIf(window -> window.index < index - 1);
    }

    static String maskKey(String key) {
        if (!key.startsWith("ip:")) {
            return key;
        }
        String address = key.substring(3);
        int cut = address.contains(":") ? nthIndexOf(address, ':', 3) : address.lastIndexOf('.');
        return cut < 0 ? "ip:***" : "ip:" + address.substring(0, cut + 1) + "*";
    }

    private static int nthIndexOf(String text, char target, int n) {
        int found = -1;
        for (int i = 0; i < n; i++) {
            found = text.indexOf(target, found + 1);
            if (found < 0) {
                return -1;
            }
        }
        return found;
    }

    private static final class Window {

        private long index;
        private int previous;
        private int current;
        private boolean reported;

        private Window(long index) {
            this.index = index;
        }

        private void advance(long next) {
            if (next == index) {
                return;
            }
            previous = next == index + 1 ? current : 0;
            current = 0;
            reported = false;
            index = next;
        }

        private long tryAcquire(int limit, long size, long elapsed) {
            if (previous * (size - elapsed) + (current + 1L) * size <= limit * size) {
                current++;
                return 0;
            }
            long wait;
            if (current + 1 <= limit) {
                wait = size - (limit - current - 1L) * size / previous - elapsed;
            } else {
                wait = size - elapsed + size - (limit - 1L) * size / current;
            }
            return Math.max(1, wait);
        }

    }

}
