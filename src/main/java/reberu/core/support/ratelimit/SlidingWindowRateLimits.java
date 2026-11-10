package reberu.core.support.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimitExceededException;
import reberu.core.support.RateLimits;

class SlidingWindowRateLimits implements RateLimits {

    static final String SECURITY_LOGGER = "reberu.security";
    static final int EXACT_MAX_LIMIT = 100;

    private static final Logger securityLog = LoggerFactory.getLogger(SECURITY_LOGGER);

    private static final long CAP_LOG_INTERVAL = Duration.ofMinutes(1).toMillis();

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<RateLimit, Map<RateLimit.Key, Pool>> pools = new EnumMap<>(RateLimit.class);

    SlidingWindowRateLimits(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        for (RateLimit rule : RateLimit.values()) {
            Map<RateLimit.Key, Pool> byKind = new EnumMap<>(RateLimit.Key.class);
            for (RateLimit.Key kind : RateLimit.Key.values()) {
                byKind.put(kind, new Pool());
            }
            pools.put(rule, byKind);
        }
    }

    @Override
    public void acquire(RateLimit rule, String key) {
        RateLimitProperties.Rule config = properties.rules().get(rule);
        RateLimit.Key kind = key.startsWith(RateLimits.IP_PREFIX) ? RateLimit.Key.IP : RateLimit.Key.ACCOUNT;
        Pool pool = pools.get(rule).get(kind);
        ConcurrentHashMap<String, Bucket> keys = pool.buckets;
        long size = config.window().toMillis();
        long now = clock.millis();
        if (!keys.containsKey(key) && keys.size() >= properties.maxKeysPerRule()) {
            purge(keys, now, size);
            if (keys.size() >= properties.maxKeysPerRule()) {
                long logIndex = Math.floorDiv(now, CAP_LOG_INTERVAL);
                if (pool.lastCapLog.getAndSet(logIndex) != logIndex) {
                    securityLog.warn("요청 제한 키 상한 도달: rule={}, kind={}", rule.id(), kind);
                }
                throw new RateLimitExceededException(rule, config.window());
            }
        }
        long[] wait = {0};
        boolean[] report = {false};
        keys.compute(key, (ignored, bucket) -> {
            Bucket current = bucket == null ? newBucket(config.limit()) : bucket;
            wait[0] = current.tryAcquire(config.limit(), size, now);
            if (wait[0] == 0) {
                current.reported = false;
            } else if (!current.reported) {
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
            long size = properties.rules().get(rule).window().toMillis();
            for (Pool pool : pools.get(rule).values()) {
                purge(pool.buckets, now, size);
            }
        }
    }

    int size(RateLimit rule) {
        return pools.get(rule).values().stream().mapToInt(pool -> pool.buckets.size()).sum();
    }

    private static void purge(ConcurrentHashMap<String, Bucket> keys, long now, long size) {
        for (String key : keys.keySet()) {
            keys.computeIfPresent(key, (ignored, bucket) -> bucket.stale(size, now) ? null : bucket);
        }
    }

    private static Bucket newBucket(int limit) {
        return limit <= EXACT_MAX_LIMIT ? new ExactBucket() : new CountingBucket();
    }

    static String maskKey(String key) {
        if (!key.startsWith(RateLimits.IP_PREFIX)) {
            return key;
        }
        String address = key.substring(RateLimits.IP_PREFIX.length());
        int cut = address.contains(":") ? nthIndexOf(address, ':', 3) : address.lastIndexOf('.');
        return RateLimits.IP_PREFIX + (cut < 0 ? "***" : address.substring(0, cut + 1) + "*");
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

    private static final class Pool {

        private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
        private final AtomicLong lastCapLog = new AtomicLong(Long.MIN_VALUE);

    }

    private abstract static class Bucket {

        private boolean reported;

        abstract long tryAcquire(int limit, long size, long now);

        abstract boolean stale(long size, long now);

    }

    private static final class ExactBucket extends Bucket {

        private final ArrayDeque<Long> times = new ArrayDeque<>();

        @Override
        long tryAcquire(int limit, long size, long now) {
            while (!times.isEmpty() && times.peekFirst() <= now - size) {
                times.pollFirst();
            }
            if (times.size() < limit) {
                times.addLast(now);
                return 0;
            }
            return Math.max(1, times.peekFirst() + size - now);
        }

        @Override
        boolean stale(long size, long now) {
            Long last = times.peekLast();
            return last == null || last <= now - size;
        }

    }

    private static final class CountingBucket extends Bucket {

        private long index = Long.MIN_VALUE;
        private int previous;
        private int current;

        @Override
        long tryAcquire(int limit, long size, long now) {
            long next = Math.floorDiv(now, size);
            if (next != index) {
                previous = next == index + 1 ? current : 0;
                current = 0;
                index = next;
            }
            long elapsed = now - index * size;
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

        @Override
        boolean stale(long size, long now) {
            return index < Math.floorDiv(now, size) - 1;
        }

    }

}
