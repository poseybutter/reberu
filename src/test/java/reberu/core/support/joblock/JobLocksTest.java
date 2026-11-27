package reberu.core.support.joblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.TestcontainersConfiguration;
import reberu.core.support.JobLocks;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobLocksTest {

    private static final Duration LEASE = Duration.ofMinutes(2);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobLocks registered;

    @Test
    @DisplayName("ARCH-EVT-12: 한 서버가 작업을 잡은 동안 다른 서버는 건너뛰고, 끝나면 다른 서버가 잡음")
    void onlyOneServerRunsAtATime() {
        String job = job();
        MovableClock clock = new MovableClock();
        JobLocks first = new JdbcJobLocks(jdbc, clock);
        JobLocks second = new JdbcJobLocks(jdbc, clock);
        AtomicInteger runs = new AtomicInteger();

        boolean ran = first.runExclusively(job, LEASE, () -> {
            runs.incrementAndGet();
            assertThat(second.runExclusively(job, LEASE, runs::incrementAndGet)).isFalse();
            assertThat(first.runExclusively(job, LEASE, runs::incrementAndGet)).isFalse();
        });

        assertThat(ran).isTrue();
        assertThat(runs).hasValue(1);
        assertThat(second.runExclusively(job, LEASE, runs::incrementAndGet)).isTrue();
        assertThat(first.runExclusively(job, LEASE, runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(3);
    }

    @Test
    @DisplayName("ARCH-EVT-12: 작업이 예외로 끝나도 잠금을 풀고 예외는 그대로 던짐")
    void releasesAfterFailure() {
        String job = job();
        JobLocks locks = new JdbcJobLocks(jdbc, new MovableClock());

        assertThatThrownBy(() -> locks.runExclusively(job, LEASE, () -> {
            throw new IllegalStateException("작업 실패 재현");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(locks.runExclusively(job, LEASE, () -> { })).isTrue();
    }

    @Test
    @DisplayName("ARCH-EVT-12: 잡은 서버가 멈추면 기한이 지난 뒤에만 다른 서버가 넘겨받고, 늦게 끝난 서버는 남의 잠금을 풀지 않음")
    void takesOverAfterLease() throws Exception {
        String job = job();
        MovableClock clock = new MovableClock();
        Instant start = clock.instant();
        JobLocks stuck = new JdbcJobLocks(jdbc, clock);
        JobLocks other = new JdbcJobLocks(jdbc, clock);
        JobLocks third = new JdbcJobLocks(jdbc, clock);
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Future<Boolean>> takeover = new AtomicReference<>();
            stuck.runExclusively(job, LEASE, () -> {
                clock.set(start.plus(LEASE).minusMillis(1));
                assertThat(other.runExclusively(job, LEASE, () -> { })).isFalse();
                clock.set(start.plus(LEASE));
                takeover.set(pool.submit(() -> other.runExclusively(job, LEASE, () -> {
                    taken.countDown();
                    await(finish);
                })));
                await(taken);
            });

            assertThat(third.runExclusively(job, LEASE, () -> { })).isFalse();
            finish.countDown();
            assertThat(takeover.get().get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(third.runExclusively(job, LEASE, () -> { })).isTrue();
        }
        finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ARCH-EVT-12: 처음 쓰는 작업을 여러 서버가 함께 잡으려 해도 하나만 실행")
    void onlyOneWinsTheFirstRace() throws Exception {
        String job = job();
        int servers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(servers);
        CountDownLatch ready = new CountDownLatch(servers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(servers - 1);
        AtomicInteger running = new AtomicInteger();
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < servers; i++) {
                JobLocks locks = new JdbcJobLocks(jdbc, Clock.systemUTC());
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    boolean ran = locks.runExclusively(job, LEASE, () -> {
                        running.incrementAndGet();
                        await(done);
                    });
                    if (!ran) {
                        done.countDown();
                    }
                    return ran;
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int ran = 0;
            for (Future<Boolean> result : results) {
                ran += result.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(ran).isEqualTo(1);
            assertThat(running).hasValue(1);
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ARCH-EVT-12: 실제 서버에 잠금 등록, 이름·기한이 틀리면 거부")
    void rejectsInvalidArguments() {
        assertThat(registered.runExclusively(job(), LEASE, () -> { })).isTrue();
        assertThatThrownBy(() -> registered.runExclusively(" ", LEASE, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registered.runExclusively("x".repeat(61), LEASE, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registered.runExclusively(job(), Duration.ZERO, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String job() {
        return "test-" + UUID.randomUUID();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("다른 서버의 시도 대기 시간 초과");
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    static class MovableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

    }

}
