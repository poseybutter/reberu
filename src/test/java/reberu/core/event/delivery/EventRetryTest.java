package reberu.core.event.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import reberu.TestcontainersConfiguration;
import reberu.core.event.DomainEventConfiguration;
import reberu.core.event.DomainEventListener;
import reberu.core.support.JobLocks;
import reberu.core.support.joblock.JobLockConfiguration;

@ExtendWith(OutputCaptureExtension.class)
class EventRetryTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static final List<Duration> INTERVALS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(12));

    private static final JdbcDatabaseContainer<?> DATABASE = TestcontainersConfiguration.newSelectedContainer();

    private static ConfigurableApplicationContext server;

    @BeforeAll
    static void start() {
        DATABASE.start();
        server = startServer();
    }

    @AfterAll
    static void stop() {
        server.close();
        DATABASE.stop();
    }

    @AfterEach
    void cleanUp() {
        RetryListener.FAILING.clear();
        jdbc(server).sql("DELETE FROM EVENT_PUBLICATION").update();
    }

    @Test
    @DisplayName("ARCH-EVT-04: 실패한 기록은 간격이 지난 뒤에만 다시 전달, 다섯 번 뒤에는 멈추고 한 번만 기록")
    void retriesOnScheduleThenGivesUp(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        RetryListener.FAILING.add(id);
        MovableClock clock = new MovableClock();
        EventRetry retry = retry(server, clock);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        retry.bindTo(registry);
        publish(server, id);
        awaitFailed(id, 1);

        for (int round = 0; round < INTERVALS.size(); round++) {
            Instant due = lastAttempt(id).plus(INTERVALS.get(round));

            clock.set(due.minusSeconds(1));
            assertThat(retry.runOnce()).as("간격 전 %d번째", round + 1).isZero();
            clock.set(due);
            assertThat(retry.runOnce()).as("간격 뒤 %d번째", round + 1).isEqualTo(1);

            awaitFailed(id, round + 2);
            assertThat(RetryListener.attempts(id)).isEqualTo(round + 2);
        }

        RetryListener.FAILING.remove(id);
        clock.set(Instant.now().plus(Duration.ofDays(365)));
        assertThat(retry.runOnce()).isZero();
        assertThat(retry.runOnce()).isZero();

        assertThat(RetryListener.attempts(id)).isEqualTo(INTERVALS.size() + 1);
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(registry.get(EventRetry.EXHAUSTED).gauge().value()).isEqualTo(1);
        assertThat(output.getOut().lines().filter(line -> line.contains("이벤트 전달 포기")).toList())
                .singleElement().asString()
                .contains("id=" + publicationId(id), RetryEvent.class.getName(),
                        "attempts=" + (INTERVALS.size() + 1))
                .doesNotContain(id.toString());

        delete(id);
        assertThat(retry.runOnce()).isZero();
        assertThat(registry.get(EventRetry.EXHAUSTED).gauge().value()).isZero();
    }

    @Test
    @DisplayName("ARCH-EVT-04: 다시 전달해 성공한 기록은 완료, 더 보내지 않음")
    void completesAfterRetry() {
        UUID id = UUID.randomUUID();
        RetryListener.FAILING.add(id);
        MovableClock clock = new MovableClock();
        EventRetry retry = retry(server, clock);
        publish(server, id);
        awaitFailed(id, 1);

        RetryListener.FAILING.remove(id);
        clock.set(lastAttempt(id).plus(INTERVALS.get(0)));
        assertThat(retry.runOnce()).isEqualTo(1);

        await().atMost(WAIT).until(() -> "COMPLETED".equals(status(id)));
        clock.set(Instant.now().plus(Duration.ofDays(365)));
        assertThat(retry.runOnce()).isZero();
        assertThat(RetryListener.attempts(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("ARCH-EVT-12: 서버 둘이 같은 때 재시도해도 한 번만 다시 전달")
    void twoServersResubmitOnce() throws Exception {
        UUID id = UUID.randomUUID();
        RetryListener.FAILING.add(id);
        publish(server, id);
        awaitFailed(id, 1);
        Instant due = lastAttempt(id).plus(INTERVALS.get(0));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ConfigurableApplicationContext other = startServer()) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = List.of(server, other).stream().map(context -> pool.submit(() -> {
                MovableClock clock = new MovableClock();
                clock.set(due);
                EventRetry retry = retry(context, clock);
                ready.countDown();
                go.await();
                return retry.runOnce();
            })).toList();
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Integer> result : results) {
                result.get(60, TimeUnit.SECONDS);
            }

            awaitFailed(id, 2);
            await().during(Duration.ofSeconds(2)).atMost(WAIT).until(() -> RetryListener.attempts(id) == 2);
            assertThat(attempts(id)).isEqualTo(2);
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ARCH-EVT-12: 다른 서버가 재시도 작업을 잡고 있는 동안에는 건너뛰고, 풀린 뒤의 상태로 다시 판정")
    void skipsWhileAnotherServerHoldsTheJob() {
        UUID id = UUID.randomUUID();
        RetryListener.FAILING.add(id);
        MovableClock clock = new MovableClock();
        publish(server, id);
        awaitFailed(id, 1);
        clock.set(lastAttempt(id).plus(INTERVALS.get(0)));

        try (ConfigurableApplicationContext other = startServer()) {
            EventRetry mine = retry(server, clock);
            EventRetry theirs = retry(other, clock);
            boolean held = other.getBean(JobLocks.class).runExclusively(EventRetry.JOB, EventRetry.LEASE, () -> {
                assertThat(mine.runOnce()).isZero();
                assertThat(attempts(id)).isEqualTo(1);
            });
            assertThat(held).isTrue();

            assertThat(theirs.runOnce()).isEqualTo(1);
            awaitFailed(id, 2);
            assertThat(mine.runOnce()).isZero();
            assertThat(theirs.runOnce()).isZero();
            assertThat(attempts(id)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("ARCH-EVT-04: 처리 중에 멈춘 기록은 실패로 바뀐 뒤 다시 전달, 그 전에는 건드리지 않음")
    void recoversStuckPublication() {
        UUID id = UUID.randomUUID();
        RetryListener.FAILING.add(id);
        MovableClock clock = new MovableClock();
        publish(server, id);
        awaitFailed(id, 1);
        Instant due = lastAttempt(id).plus(INTERVALS.get(0));
        clock.set(due);

        jdbc(server).sql("UPDATE EVENT_PUBLICATION SET status = 'PROCESSING' WHERE serialized_event LIKE :event")
                .param("event", "%" + id + "%").update();
        assertThat(retry(server, clock).runOnce()).isZero();
        assertThat(RetryListener.attempts(id)).isEqualTo(1);

        RetryListener.FAILING.remove(id);
        try (ConfigurableApplicationContext watching = startServer(
                "--spring.modulith.events.staleness.processing=1s",
                "--spring.modulith.events.staleness.check-interval=1s")) {
            await().atMost(WAIT).until(() -> "FAILED".equals(status(id)));
            assertThat(retry(watching, clock).runOnce()).isEqualTo(1);
            await().atMost(WAIT).until(() -> "COMPLETED".equals(status(id)));
        }
    }

    @Test
    @DisplayName("ARCH-EVT-08: 보관 기간이 지난 완료 기록만 삭제, 미완료 기록은 오래돼도 남김")
    void deletesOnlyExpiredCompletedPublications() {
        UUID expired = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        RetryListener.FAILING.add(failed);
        publish(server, expired);
        publish(server, recent);
        publish(server, failed);
        await().atMost(WAIT).until(() -> "COMPLETED".equals(status(expired)) && "COMPLETED".equals(status(recent)));
        awaitFailed(failed, 1);
        Instant now = Instant.now();
        age(expired, now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(30)).minusSeconds(60));
        age(recent, now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(30)).plusSeconds(60));
        age(failed, now.minus(Duration.ofDays(40)), null);

        new EventPublicationCleanup(server.getBean(CompletedEventPublications.class), Duration.ofDays(30))
                .deleteExpired();

        assertThat(status(expired)).isNull();
        assertThat(status(recent)).isEqualTo("COMPLETED");
        assertThat(status(failed)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("ARCH-EVT-04: 설정한 간격·확인 간격으로 재시도 작업 등록, 재시작 때 한꺼번에 다시 보내지 않음")
    void registersScheduledRetry() {
        UUID id = UUID.randomUUID();
        RetryListener.FAILING.add(id);
        publish(server, id);
        awaitFailed(id, 1);
        RetryListener.FAILING.remove(id);

        try (ConfigurableApplicationContext restarted = startServer()) {
            await().during(Duration.ofSeconds(3)).atMost(WAIT).until(() -> RetryListener.attempts(id) == 1);
        }
        try (ConfigurableApplicationContext fast = startServer("--reberu.events.retry.intervals=1s",
                "--reberu.events.retry.check-interval=PT1S")) {
            await().atMost(WAIT).until(() -> "COMPLETED".equals(status(id)));
        }
        assertThat(RetryListener.attempts(id)).isEqualTo(2);
    }

    private static ConfigurableApplicationContext startServer(String... extra) {
        String[] base = {"--spring.datasource.url=" + DATABASE.getJdbcUrl(),
                "--spring.datasource.username=" + DATABASE.getUsername(),
                "--spring.datasource.password=" + DATABASE.getPassword(),
                "--spring.task.execution.shutdown.await-termination=true",
                "--management.server.port=0"};
        String[] arguments = new String[base.length + extra.length];
        System.arraycopy(base, 0, arguments, 0, base.length);
        System.arraycopy(extra, 0, arguments, base.length, extra.length);
        return new SpringApplicationBuilder(RetryTestServer.class).web(WebApplicationType.NONE).run(arguments);
    }

    private static EventRetry retry(ConfigurableApplicationContext context, Clock clock) {
        return new EventRetry(context.getBean(IncompleteEventPublications.class), INTERVALS, clock,
                context.getBean(JobLocks.class));
    }

    private static void publish(ConfigurableApplicationContext context, UUID id) {
        context.getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> context.publishEvent(new RetryEvent(id)));
    }

    private static void awaitFailed(UUID id, int attempts) {
        await().atMost(WAIT).until(() -> "FAILED".equals(status(id)) && attempts(id) == attempts
                && RetryListener.attempts(id) == attempts);
    }

    private static Instant lastAttempt(UUID id) {
        AtomicReference<Instant> last = new AtomicReference<>();
        server.getBean(IncompleteEventPublications.class).resubmitIncompletePublications(publication -> {
            if (publication.getEvent() instanceof RetryEvent event && event.id().equals(id)) {
                last.set(publication.getLastPublicationDate());
            }
            return false;
        });
        assertThat(last.get()).isNotNull();
        return last.get();
    }

    private static JdbcClient jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class);
    }

    private static String status(UUID id) {
        return jdbc(server).sql("SELECT status FROM EVENT_PUBLICATION WHERE serialized_event LIKE :event")
                .param("event", "%" + id + "%").query(String.class).optional().orElse(null);
    }

    private static int attempts(UUID id) {
        return jdbc(server).sql("SELECT completion_attempts FROM EVENT_PUBLICATION WHERE serialized_event LIKE :event")
                .param("event", "%" + id + "%").query(Integer.class).single();
    }

    private static String publicationId(UUID id) {
        return jdbc(server).sql("SELECT CAST(id AS CHAR(36)) FROM EVENT_PUBLICATION WHERE serialized_event LIKE :event")
                .param("event", "%" + id + "%").query(String.class).single();
    }

    private static void age(UUID id, Instant publishedAt, Instant completedAt) {
        jdbc(server).sql("""
                        UPDATE EVENT_PUBLICATION SET publication_date = :published, completion_date = :completed
                        WHERE serialized_event LIKE :event
                        """)
                .param("published", Timestamp.from(publishedAt))
                .param("completed", completedAt == null ? null : Timestamp.from(completedAt))
                .param("event", "%" + id + "%")
                .update();
    }

    private static void delete(UUID id) {
        jdbc(server).sql("DELETE FROM EVENT_PUBLICATION WHERE serialized_event LIKE :event")
                .param("event", "%" + id + "%").update();
    }

    record RetryEvent(UUID id) {
    }

    static class RetryListener {

        static final Set<UUID> FAILING = ConcurrentHashMap.newKeySet();
        static final Map<UUID, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();

        static int attempts(UUID id) {
            AtomicInteger count = ATTEMPTS.get(id);
            return count == null ? 0 : count.get();
        }

        @DomainEventListener
        void on(RetryEvent event) {
            ATTEMPTS.computeIfAbsent(event.id(), key -> new AtomicInteger()).incrementAndGet();
            if (FAILING.contains(event.id())) {
                throw new IllegalStateException("테스트 구독자 처리 실패");
            }
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
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({DomainEventConfiguration.class, EventRetryConfiguration.class, JobLockConfiguration.class,
            RetryListener.class})
    static class RetryTestServer {
    }

}
