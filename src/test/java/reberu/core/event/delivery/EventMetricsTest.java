package reberu.core.event.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.ClassUtils;
import reberu.TestcontainersConfiguration;
import reberu.core.event.DomainEventListener;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventMetricsTest {

    private static final String MARKER = "reberu.metrics.test";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry registry;

    @Autowired
    EventBacklogMetrics backlog;

    @Autowired
    ApplicationContext context;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM EVENT_PUBLICATION WHERE listener_id = :marker").param("marker", MARKER).update();
        backlog.refresh();
    }

    @Test
    @DisplayName("OPS-OBS-07: 미완료 발행 기록 수와 가장 오래된 기록의 나이")
    void reportsBacklog() {
        backlog.refresh();
        double before = registry.get(EventBacklogMetrics.INCOMPLETE).gauge().value();
        Instant now = Instant.now();
        insert(now.minus(Duration.ofMinutes(10)), null);
        insert(now.minus(Duration.ofMinutes(3)), null);
        insert(now.minus(Duration.ofHours(2)), now);

        backlog.refresh();

        assertThat(registry.get(EventBacklogMetrics.INCOMPLETE).gauge().value() - before).isEqualTo(2);
        if (before == 0) {
            assertThat(registry.get(EventBacklogMetrics.OLDEST_AGE).gauge().value()).isBetween(600.0, 660.0);
        }

        cleanUp();

        assertThat(registry.get(EventBacklogMetrics.INCOMPLETE).gauge().value()).isEqualTo(before);
        if (before == 0) {
            assertThat(registry.get(EventBacklogMetrics.OLDEST_AGE).gauge().value()).isZero();
        }
    }

    @Test
    @DisplayName("OPS-OBS-07: 구독자 실패는 구독 메서드 이름으로만 집계, 성공은 세지 않음")
    void countsListenerFailuresByMethod() {
        ProxyFactory factory = new ProxyFactory(new SampleListener());
        factory.setProxyTargetClass(true);
        factory.addAdvice(new DeliveryFailureMeter());
        SampleListener listener = (SampleListener) factory.getProxy();
        double before = failures();

        listener.on("ok");
        assertThatThrownBy(() -> listener.on("fail " + UUID.randomUUID())).isInstanceOf(IllegalStateException.class);

        assertThat(failures() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("OPS-OBS-07: 실제 구독자 빈에 실패 집계 적용")
    void advisesEveryDomainEventListener() {
        int listeners = 0;
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name, false);
            type = type == null ? null : ClassUtils.getUserClass(type);
            if (type == null || !type.getName().startsWith("reberu.") || Arrays.stream(type.getDeclaredMethods())
                    .noneMatch(method -> method.isAnnotationPresent(DomainEventListener.class))) {
                continue;
            }
            listeners++;
            Object bean = context.getBean(name);
            assertThat(bean).as(name).isInstanceOf(Advised.class);
            assertThat(Arrays.stream(((Advised) bean).getAdvisors())
                    .anyMatch(advisor -> advisor.getAdvice() instanceof DeliveryFailureMeter)).as(name).isTrue();
        }
        assertThat(listeners).isPositive();
    }

    private double failures() {
        return registry.counter(DeliveryFailureMeter.FAILURES, DeliveryFailureMeter.LISTENER, "SampleListener.on")
                .count();
    }

    private void insert(Instant publishedAt, Instant completedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO EVENT_PUBLICATION (id, listener_id, event_type, serialized_event, publication_date) "
                        + "VALUES ('" + id + "', :marker, 'sample', '{}', :published)")
                .param("marker", MARKER)
                .param("published", Timestamp.from(publishedAt))
                .update();
        if (completedAt != null) {
            jdbc.sql("UPDATE EVENT_PUBLICATION SET completion_date = :completed WHERE id = '" + id + "'")
                    .param("completed", Timestamp.from(completedAt))
                    .update();
        }
    }

    static class SampleListener {

        void on(String event) {
            if (event.startsWith("fail")) {
                throw new IllegalStateException("구독자 실패 재현");
            }
        }

    }

}
