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
    public void acquire(RateLimit rule, String key, int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("요청 제한 개수는 1 이상: " + permits);
        }
        RateLimitProperties.Rule config = properties.rules().get(rule);
        if (permits > config.limit()) {
            throw new RateLimitExceededException(rule, config.window());
        }
        RateLimit.Key kind = key.startsWith(RateLimits.IP_PREFIX) ? RateLimit.Key.IP : RateLimit.Key.ACCOUNT;
        Pool pool = pools.get(rule).get(kind);
        ConcurrentHashMap<String, Bucket> keys = pool.buckets;
        long size = config.window().toMillis();
        long now = clock.millis();
        long[] wait = {-1};
        boolean[] report = {false};
        while (wait[0] < 0) {
            keys.computeIfPresent(key, (ignored, bucket) -> {
                wait[0] = bucket.tryAcquire(config.limit(), permits, size, now);
                report[0] = bucket.mark(wait[0]);
                return bucket;
            });
            if (wait[0] >= 0) {
                break;
            }
            synchronized (pool) {
                if (keys.containsKey(key)) {
                    continue;
                }
                if (keys.size() >= properties.maxKeysPerRule()) {
                    purge(keys, now, size);
                }
                if (keys.size() >= properties.maxKeysPerRule()) {
                    long logIndex = Math.floorDiv(now, CAP_LOG_INTERVAL);
                    if (pool.lastCapLog.getAndAccumulate(logIndex, Math::max) < logIndex) {
                        securityLog.warn("요청 제한 키 상한 도달: rule={}, kind={}", rule.id(), kind);
                    }
                    throw new RateLimitExceededException(rule, config.window());
                }
                Bucket created = newBucket(config.limit());
                wait[0] = created.tryAcquire(config.limit(), permits, size, now);
                report[0] = created.mark(wait[0]);
                keys.put(key, created);
            }
        }
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

        boolean mark(long wait) {
            if (wait == 0) {
                reported = false;
                return false;
            }
            if (reported) {
                return false;
            }
            reported = true;
            return true;
        }

        abstract long tryAcquire(int limit, int permits, long size, long now);

        abstract boolean stale(long size, long now);

    }

    private static final class ExactBucket extends Bucket {

        private final ArrayDeque<Long> times = new ArrayDeque<>();

        @Override
        long tryAcquire(int limit, int permits, long size, long now) {
            while (!times.isEmpty() && times.peekFirst() <= now - size) {
                times.pollFirst();
            }
            if (times.size() + permits <= limit) {
                for (int i = 0; i < permits; i++) {
                    times.addLast(now);
                }
                return 0;
            }
            int expiring = times.size() + permits - limit;
            long freedAt = times.stream().skip(expiring - 1L).findFirst().orElseThrow();
            return Math.max(1, freedAt + size - now);
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
        long tryAcquire(int limit, int permits, long size, long now) {
            long next = Math.floorDiv(now, size);
            if (next != index) {
                previous = next == index + 1 ? current : 0;
                current = 0;
                index = next;
            }
            long elapsed = now - index * size;
            if (previous * (size - elapsed) + ((long) current + permits) * size <= limit * size) {
                current += permits;
                return 0;
            }
            long wait;
            if ((long) current + permits <= limit) {
                wait = size - ((long) limit - current - permits) * size / previous - elapsed;
            } else {
                wait = size - elapsed + size - ((long) limit - permits) * size / current;
            }
            return Math.max(1, wait);
        }

        @Override
        boolean stale(long size, long now) {
            return index < Math.floorDiv(now, size) - 1;
        }

    }

}
