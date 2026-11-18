package reberu.core.support.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reberu.core.support.FailureStreaks;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimitExceededException;

class MemoryFailureStreaks implements FailureStreaks {

    private static final Logger securityLog = LoggerFactory.getLogger(SlidingWindowRateLimits.SECURITY_LOGGER);

    private static final long CAP_LOG_INTERVAL = Duration.ofMinutes(1).toMillis();

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<RateLimit, ConcurrentHashMap<String, Streak>> streaks = new EnumMap<>(RateLimit.class);
    private final AtomicLong lastCapLog = new AtomicLong(-1);

    MemoryFailureStreaks(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        properties.failureStreaks().keySet().forEach(rule -> streaks.put(rule, new ConcurrentHashMap<>()));
    }

    @Override
    public void check(RateLimit rule, String key) {
        Map<String, Streak> keys = keys(rule);
        Streak streak = keys.get(key);
        long now = clock.millis();
        if (streak != null && streak.blockedUntil() > now) {
            throw new RateLimitExceededException(rule, Duration.ofMillis(streak.blockedUntil() - now));
        }
    }

    @Override
    public void failed(RateLimit rule, String key) {
        RateLimitProperties.Streak config = properties.failureStreaks().get(rule);
        ConcurrentHashMap<String, Streak> keys = keys(rule);
        long now = clock.millis();
        long block = config.block().toMillis();
        boolean[] blocked = {false};
        BiFunction<String, Streak, Streak> next = (ignored, streak) -> {
            if (streak != null && streak.blockedUntil() > now) {
                return streak;
            }
            int count = streak == null || streak.stale(now, block) ? 1 : streak.count() + 1;
            if (count >= config.threshold()) {
                blocked[0] = true;
                return new Streak(0, now, now + block);
            }
            return new Streak(count, now, 0);
        };
        if (keys.computeIfPresent(key, next) == null) {
            synchronized (keys) {
                if (!keys.containsKey(key) && keys.size() >= properties.maxKeysPerRule()) {
                    keys.entrySet().removeIf(entry -> entry.getValue().stale(now, block));
                    if (keys.size() >= properties.maxKeysPerRule()) {
                        long logIndex = Math.floorDiv(now, CAP_LOG_INTERVAL);
                        if (lastCapLog.getAndAccumulate(logIndex, Math::max) < logIndex) {
                            securityLog.warn("실패 연속 기록 키 상한 도달: rule={}", rule.id());
                        }
                        return;
                    }
                }
                keys.compute(key, next);
            }
        }
        if (blocked[0]) {
            securityLog.warn("실패 연속 차단 시작: rule={}, key={}, seconds={}", rule.id(),
                    SlidingWindowRateLimits.maskKey(key), config.block().toSeconds());
        }
    }

    @Override
    public void succeeded(RateLimit rule, String key) {
        keys(rule).computeIfPresent(key, (ignored, streak) -> streak.blockedUntil() > clock.millis() ? streak : null);
    }

    int size(RateLimit rule) {
        return keys(rule).size();
    }

    private ConcurrentHashMap<String, Streak> keys(RateLimit rule) {
        ConcurrentHashMap<String, Streak> keys = streaks.get(rule);
        if (keys == null) {
            throw new IllegalArgumentException("실패 연속 차단 설정 없는 규칙: " + rule.id());
        }
        return keys;
    }

    private record Streak(int count, long lastFailure, long blockedUntil) {

        boolean stale(long now, long block) {
            return blockedUntil <= now && now - lastFailure >= block;
        }

    }

}
