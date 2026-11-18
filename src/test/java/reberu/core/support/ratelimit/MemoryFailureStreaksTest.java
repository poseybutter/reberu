package reberu.core.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimitExceededException;
import reberu.core.support.RateLimits;

@ExtendWith(OutputCaptureExtension.class)
class MemoryFailureStreaksTest {

    private static final Duration BLOCK = Duration.ofMinutes(15);
    private static final String KEY = RateLimits.ipKey("203.0.113.7");

    private final SlidingWindowRateLimitsTest.MutableClock clock =
            new SlidingWindowRateLimitsTest.MutableClock(Instant.parse("2026-11-18T00:00:00Z"));

    @Test
    @DisplayName("SEC-RATE-02: 실패가 연속 20번이면 15분 차단, Retry-After는 남은 시간, 다른 키는 그대로")
    void blocksAfterConsecutiveFailures(CapturedOutput output) {
        MemoryFailureStreaks streaks = streaks(20, 100);

        for (int i = 0; i < 19; i++) {
            streaks.failed(RateLimit.INVITE_LINK, KEY);
        }
        assertThatCode(() -> streaks.check(RateLimit.INVITE_LINK, KEY)).doesNotThrowAnyException();
        streaks.failed(RateLimit.INVITE_LINK, KEY);

        RateLimitExceededException blocked = catchThrowableOfType(RateLimitExceededException.class,
                () -> streaks.check(RateLimit.INVITE_LINK, KEY));
        assertThat(blocked.rule()).isEqualTo(RateLimit.INVITE_LINK);
        assertThat(blocked.retryAfterSeconds()).isEqualTo("900");
        assertThatCode(() -> streaks.check(RateLimit.INVITE_LINK, RateLimits.ipKey("203.0.113.8")))
                .doesNotThrowAnyException();
        assertThat(output).contains("rule=SEC-RATE-02, key=ip:203.0.113.*").doesNotContain("203.0.113.7");

        clock.advance(Duration.ofMinutes(10));
        assertThat(catchThrowableOfType(RateLimitExceededException.class,
                () -> streaks.check(RateLimit.INVITE_LINK, KEY)).retryAfterSeconds()).isEqualTo("300");
        streaks.succeeded(RateLimit.INVITE_LINK, KEY);
        assertThatThrownBy(() -> streaks.check(RateLimit.INVITE_LINK, KEY))
                .isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofMinutes(5));
        assertThatCode(() -> streaks.check(RateLimit.INVITE_LINK, KEY)).doesNotThrowAnyException();
        streaks.failed(RateLimit.INVITE_LINK, KEY);
        assertThatCode(() -> streaks.check(RateLimit.INVITE_LINK, KEY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-RATE-02: 성공하면 연속 실패 수가 0으로, 마지막 실패에서 15분이 지나도 0으로")
    void successAndQuietTimeReset() {
        MemoryFailureStreaks streaks = streaks(3, 100);

        streaks.failed(RateLimit.INVITE_LINK, KEY);
        streaks.failed(RateLimit.INVITE_LINK, KEY);
        streaks.succeeded(RateLimit.INVITE_LINK, KEY);
        streaks.failed(RateLimit.INVITE_LINK, KEY);
        streaks.failed(RateLimit.INVITE_LINK, KEY);
        assertThatCode(() -> streaks.check(RateLimit.INVITE_LINK, KEY)).doesNotThrowAnyException();

        clock.advance(BLOCK);
        streaks.failed(RateLimit.INVITE_LINK, KEY);
        assertThatCode(() -> streaks.check(RateLimit.INVITE_LINK, KEY)).doesNotThrowAnyException();
        streaks.failed(RateLimit.INVITE_LINK, KEY);
        streaks.failed(RateLimit.INVITE_LINK, KEY);
        assertThatThrownBy(() -> streaks.check(RateLimit.INVITE_LINK, KEY))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("SEC-RATE-14: 키 수 상한에 닿으면 오래된 기록을 지우고, 그래도 가득 차면 새 키는 기록하지 않고 1분에 한 번 로그")
    void keyCapPurgesThenSkips(CapturedOutput output) {
        MemoryFailureStreaks streaks = streaks(2, 2);
        String first = RateLimits.ipKey("198.51.100.1");
        String second = RateLimits.ipKey("198.51.100.2");
        String third = RateLimits.ipKey("198.51.100.3");

        streaks.failed(RateLimit.INVITE_LINK, first);
        streaks.failed(RateLimit.INVITE_LINK, second);
        streaks.failed(RateLimit.INVITE_LINK, third);
        streaks.failed(RateLimit.INVITE_LINK, third);
        assertThatCode(() -> streaks.check(RateLimit.INVITE_LINK, third)).doesNotThrowAnyException();
        assertThat(output.getOut().split("실패 연속 기록 키 상한 도달", -1)).hasSize(2);

        clock.advance(BLOCK);
        streaks.failed(RateLimit.INVITE_LINK, third);
        streaks.failed(RateLimit.INVITE_LINK, third);
        assertThatThrownBy(() -> streaks.check(RateLimit.INVITE_LINK, third))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("SEC-RATE-16: 서로 다른 새 키의 실패가 동시에 몰려도 기억하는 키 수가 상한을 넘지 않음")
    void concurrentNewKeysStayWithinCap() throws Exception {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 50; round++) {
                MemoryFailureStreaks streaks = streaks(20, 4);
                streaks.failed(RateLimit.INVITE_LINK, RateLimits.ipKey("192.0.2.250"));
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    String key = RateLimits.ipKey("192.0.2." + i);
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        if (start.await(10, TimeUnit.SECONDS)) {
                            streaks.failed(RateLimit.INVITE_LINK, key);
                        }
                        return null;
                    }));
                }
                boolean allReady = ready.await(10, TimeUnit.SECONDS);
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
                assertThat(allReady).isTrue();
                assertThat(streaks.size(RateLimit.INVITE_LINK)).as("round " + round).isLessThanOrEqualTo(4);
            }
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("실패 연속 차단 설정 값 검사: 횟수 1 이상, 차단 1초 이상")
    void validatesProperties() {
        assertThatThrownBy(() -> new RateLimitProperties(100, rules(),
                Map.of(RateLimit.INVITE_LINK, new RateLimitProperties.Streak(0, BLOCK))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RateLimitProperties(100, rules(),
                Map.of(RateLimit.INVITE_LINK, new RateLimitProperties.Streak(3, Duration.ofMillis(500)))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> streaks(3, 100).check(RateLimit.LOGIN, KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MemoryFailureStreaks streaks(int threshold, int maxKeys) {
        return new MemoryFailureStreaks(new RateLimitProperties(maxKeys, rules(),
                Map.of(RateLimit.INVITE_LINK, new RateLimitProperties.Streak(threshold, BLOCK))), clock);
    }

    private static Map<RateLimit, RateLimitProperties.Rule> rules() {
        Map<RateLimit, RateLimitProperties.Rule> rules = new EnumMap<>(RateLimit.class);
        for (RateLimit rule : RateLimit.values()) {
            rules.put(rule, new RateLimitProperties.Rule(10, Duration.ofMinutes(1)));
        }
        return rules;
    }

}
