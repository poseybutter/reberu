package reberu.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import reberu.TestcontainersConfiguration;

class EventPublicationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static final JdbcDatabaseContainer<?> DATABASE = TestcontainersConfiguration.newSelectedContainer();

    @BeforeAll
    static void startDatabase() {
        DATABASE.start();
    }

    @AfterAll
    static void stopDatabase() {
        DATABASE.stop();
    }

    @Test
    @DisplayName("원인 트랜잭션 롤백 시 발행 기록·전달 없음")
    void rolledBackEventIsNeitherRecordedNorDelivered() {
        UUID rolledBack = UUID.randomUUID();
        UUID committed = UUID.randomUUID();

        try (ConfigurableApplicationContext context = startServer()) {
            TransactionTemplate transaction = context.getBean(TransactionTemplate.class);
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new SampleEvent(rolledBack));
                status.setRollbackOnly();
            });
            transaction.executeWithoutResult(status -> context.publishEvent(new SampleEvent(committed)));

            await().atMost(WAIT).until(() -> isCompleted(context, committed));
            context.getBean(IncompleteEventPublications.class).resubmitIncompletePublications(about(rolledBack));

            assertThat(SampleListener.attempts(rolledBack)).isZero();
            assertThat(isCompleted(context, rolledBack)).isFalse();
        }
    }

    @Test
    @DisplayName("구독자 실패 시 미완료로 남고 다시 보내면 완료")
    void failedPublicationCompletesAfterResubmission() {
        UUID id = UUID.randomUUID();
        SampleListener.FAILING.add(id);

        try (ConfigurableApplicationContext context = startServer()) {
            publishCommitted(context, id);

            await().atMost(WAIT).until(() -> SampleListener.attempts(id) >= 1);
            assertThat(isCompleted(context, id)).isFalse();

            SampleListener.FAILING.remove(id);
            IncompleteEventPublications incomplete = context.getBean(IncompleteEventPublications.class);
            await().atMost(WAIT).untilAsserted(() -> {
                incomplete.resubmitIncompletePublications(about(id));
                assertThat(isCompleted(context, id)).isTrue();
            });
            assertThat(SampleListener.HANDLED).contains(id);
        }
    }

    @Test
    @DisplayName("서버 재시작 뒤 미완료 기록 이어서 전달")
    void incompletePublicationIsDeliveredAfterRestart() {
        UUID id = UUID.randomUUID();
        SampleListener.FAILING.add(id);

        try (ConfigurableApplicationContext context = startServer()) {
            publishCommitted(context, id);
            await().atMost(WAIT).until(() -> SampleListener.attempts(id) >= 1);
        }

        SampleListener.FAILING.remove(id);

        try (ConfigurableApplicationContext restarted = startServer()) {
            await().atMost(WAIT).until(() -> isCompleted(restarted, id));
            assertThat(SampleListener.HANDLED).contains(id);
        }
    }

    private static ConfigurableApplicationContext startServer() {
        return new SpringApplicationBuilder(EventTestServer.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + DATABASE.getJdbcUrl(),
                        "--spring.datasource.username=" + DATABASE.getUsername(),
                        "--spring.datasource.password=" + DATABASE.getPassword(),
                        "--spring.task.execution.shutdown.await-termination=true");
    }

    private static void publishCommitted(ConfigurableApplicationContext context, UUID id) {
        context.getBean(TransactionTemplate.class)
                .executeWithoutResult(status -> context.publishEvent(new SampleEvent(id)));
    }

    private static boolean isCompleted(ConfigurableApplicationContext context, UUID id) {
        return context.getBean(CompletedEventPublications.class).findAll().stream().anyMatch(about(id));
    }

    private static Predicate<EventPublication> about(UUID id) {
        return publication -> publication.getEvent() instanceof SampleEvent event && event.id().equals(id);
    }

    record SampleEvent(UUID id) {
    }

    static class SampleListener {

        static final Set<UUID> FAILING = ConcurrentHashMap.newKeySet();
        static final Set<UUID> HANDLED = ConcurrentHashMap.newKeySet();
        static final Map<UUID, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();

        static int attempts(UUID id) {
            AtomicInteger count = ATTEMPTS.get(id);
            return count == null ? 0 : count.get();
        }

        @ApplicationModuleListener
        void on(SampleEvent event) {
            ATTEMPTS.computeIfAbsent(event.id(), key -> new AtomicInteger()).incrementAndGet();
            if (FAILING.contains(event.id())) {
                throw new IllegalStateException("테스트 구독자 처리 실패");
            }
            HANDLED.add(event.id());
        }

    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({DomainEventConfiguration.class, SampleListener.class})
    static class EventTestServer {
    }

}
