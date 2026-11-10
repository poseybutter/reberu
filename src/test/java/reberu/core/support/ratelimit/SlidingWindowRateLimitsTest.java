package reberu.core.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reberu.core.support.ErrorCode;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimitExceededException;

class SlidingWindowRateLimitsTest {

    private static final Instant START = Instant.parse("2026-11-10T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);

    @Test
    @DisplayName("T-RATE: 한도까지 허용, 넘으면 RATE_LIMITED와 대기 시간")
    void rejectsOverLimit() {
        SlidingWindowRateLimits limits = limits(3, Duration.ofMinutes(1), 100);
        for (int i = 0; i < 3; i++) {
            limits.acquire(RateLimit.DEFAULT, "account:a");
        }

        RateLimitExceededException limited = rejected(limits, "account:a");

        assertThat(limited.code()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(limited.rule()).isEqualTo(RateLimit.DEFAULT);
        assertThat(limited.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("T-RATE: 키와 규칙마다 따로 세기")
    void countsPerKeyAndRule() {
        SlidingWindowRateLimits limits = limits(1, Duration.ofMinutes(1), 100);
        limits.acquire(RateLimit.DEFAULT, "account:a");

        assertThatCode(() -> limits.acquire(RateLimit.DEFAULT, "account:b")).doesNotThrowAnyException();
        assertThatCode(() -> limits.acquire(RateLimit.ORG_CREATE, "account:a")).doesNotThrowAnyException();
        rejected(limits, "account:a");
    }

    @Test
    @DisplayName("T-RATE: 창 경계 직후에도 한도의 두 배 불허, 지난 창 비중만큼 대기")
    void slidesAcrossBoundary() {
        SlidingWindowRateLimits limits = limits(10, Duration.ofMinutes(1), 100);
        clock.advance(Duration.ofSeconds(59));
        for (int i = 0; i < 10; i++) {
            limits.acquire(RateLimit.DEFAULT, "account:a");
        }
        clock.advance(Duration.ofSeconds(2));

        RateLimitExceededException limited = rejected(limits, "account:a");

        clock.advance(limited.retryAfter().minusMillis(1));
        rejected(limits, "account:a");
        clock.advance(Duration.ofMillis(1));
        assertThatCode(() -> limits.acquire(RateLimit.DEFAULT, "account:a")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T-RATE: 알려 준 대기 시간이 지나면 다시 허용")
    void allowsAfterRetryAfter() {
        SlidingWindowRateLimits limits = limits(2, Duration.ofMinutes(1), 100);
        limits.acquire(RateLimit.DEFAULT, "account:a");
        limits.acquire(RateLimit.DEFAULT, "account:a");

        RateLimitExceededException limited = rejected(limits, "account:a");
        clock.advance(limited.retryAfter());

        assertThatCode(() -> limits.acquire(RateLimit.DEFAULT, "account:a")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T-RATE: 거부한 요청은 세지 않음")
    void rejectedRequestsDoNotCount() {
        SlidingWindowRateLimits limits = limits(1, Duration.ofMinutes(1), 100);
        limits.acquire(RateLimit.DEFAULT, "account:a");
        for (int i = 0; i < 50; i++) {
            rejected(limits, "account:a");
        }
        clock.advance(Duration.ofMinutes(2));

        assertThatCode(() -> limits.acquire(RateLimit.DEFAULT, "account:a")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T-RATE: 키 상한에 닿으면 새 키 거부, 오래된 키 정리 뒤 허용")
    void boundsKeyCount() {
        SlidingWindowRateLimits limits = limits(5, Duration.ofMinutes(1), 2);
        limits.acquire(RateLimit.LOGIN, "ip:203.0.113.1");
        limits.acquire(RateLimit.LOGIN, "ip:203.0.113.2");

        assertThatThrownBy(() -> limits.acquire(RateLimit.LOGIN, "ip:203.0.113.3"))
                .isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limits.acquire(RateLimit.LOGIN, "ip:203.0.113.1")).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(2));
        assertThatCode(() -> limits.acquire(RateLimit.LOGIN, "ip:203.0.113.3")).doesNotThrowAnyException();
        assertThat(limits.size(RateLimit.LOGIN)).isEqualTo(1);
    }

    @Test
    @DisplayName("오래된 키 주기 정리")
    void purgesStaleKeys() {
        SlidingWindowRateLimits limits = limits(5, Duration.ofMinutes(1), 100);
        limits.acquire(RateLimit.DEFAULT, "account:a");
        clock.advance(Duration.ofMinutes(1));
        limits.purgeStale();
        assertThat(limits.size(RateLimit.DEFAULT)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        limits.purgeStale();
        assertThat(limits.size(RateLimit.DEFAULT)).isZero();
    }

    @Test
    @DisplayName("Retry-After는 올림한 초, 최소 1초")
    void retryAfterSeconds() {
        assertThat(new RateLimitExceededException(RateLimit.DEFAULT, Duration.ofMillis(1)).retryAfterSeconds())
                .isEqualTo("1");
        assertThat(new RateLimitExceededException(RateLimit.DEFAULT, Duration.ofMillis(1001)).retryAfterSeconds())
                .isEqualTo("2");
        assertThat(new RateLimitExceededException(RateLimit.DEFAULT, Duration.ZERO).retryAfterSeconds())
                .isEqualTo("1");
    }

    @Test
    @DisplayName("SEC-LOG-01: 보안 로그의 IP는 끝자리 가림, 계정 키는 그대로")
    void masksIpInLog() {
        assertThat(SlidingWindowRateLimits.maskKey("ip:203.0.113.45")).isEqualTo("ip:203.0.113.*");
        assertThat(SlidingWindowRateLimits.maskKey("ip:2001:db8:85a3:8d3:1319:8a2e:370:7348"))
                .isEqualTo("ip:2001:db8:85a3:*");
        assertThat(SlidingWindowRateLimits.maskKey("ip:unknown")).isEqualTo("ip:***");
        assertThat(SlidingWindowRateLimits.maskKey("account:a")).isEqualTo("account:a");
    }

    @Test
    @DisplayName("규칙 값이 빠지거나 잘못되면 시작 실패")
    void rejectsInvalidProperties() {
        Map<RateLimit, RateLimitProperties.Rule> rules = rules(5, Duration.ofMinutes(1));
        rules.remove(RateLimit.POSTING);
        assertThatThrownBy(() -> new RateLimitProperties(100, rules))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SEC-RATE-07");
        assertThatThrownBy(() -> new RateLimitProperties(100, rules(0, Duration.ofMinutes(1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RateLimitProperties(100, rules(5, Duration.ofMillis(500))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RateLimitProperties(0, rules(5, Duration.ofMinutes(1))))
                .isInstanceOf(IllegalStateException.class);
    }

    private SlidingWindowRateLimits limits(int limit, Duration window, int maxKeys) {
        return new SlidingWindowRateLimits(new RateLimitProperties(maxKeys, rules(limit, window)), clock);
    }

    private static Map<RateLimit, RateLimitProperties.Rule> rules(int limit, Duration window) {
        Map<RateLimit, RateLimitProperties.Rule> rules = new EnumMap<>(RateLimit.class);
        for (RateLimit rule : RateLimit.values()) {
            rules.put(rule, new RateLimitProperties.Rule(limit, window));
        }
        return rules;
    }

    private static RateLimitExceededException rejected(SlidingWindowRateLimits limits, String key) {
        try {
            limits.acquire(RateLimit.DEFAULT, key);
        }
        catch (RateLimitExceededException limited) {
            return limited;
        }
        throw new AssertionError("요청 제한 거부 없음: " + key);
    }

    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

    }

}
