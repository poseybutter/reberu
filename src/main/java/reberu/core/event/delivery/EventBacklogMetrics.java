package reberu.core.event.delivery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

class EventBacklogMetrics implements MeterBinder {

    static final String INCOMPLETE = "reberu.events.incomplete";
    static final String OLDEST_AGE = "reberu.events.incomplete.oldest.age";

    private static final long NONE = -1;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AtomicLong incomplete = new AtomicLong();
    private final AtomicLong oldestMillis = new AtomicLong(NONE);

    EventBacklogMetrics(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(INCOMPLETE, incomplete, AtomicLong::doubleValue).register(registry);
        Gauge.builder(OLDEST_AGE, this, EventBacklogMetrics::oldestAgeSeconds).baseUnit("seconds").register(registry);
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT30S")
    void refresh() {
        jdbc.sql("""
                        SELECT COUNT(*) AS incomplete, MIN(publication_date) AS oldest
                        FROM EVENT_PUBLICATION WHERE completion_date IS NULL
                        """)
                .query((rs, rowNum) -> {
                    Timestamp oldest = rs.getTimestamp("oldest");
                    incomplete.set(rs.getLong("incomplete"));
                    oldestMillis.set(oldest == null ? NONE : oldest.getTime());
                    return rowNum;
                })
                .single();
    }

    private double oldestAgeSeconds() {
        long oldest = oldestMillis.get();
        return oldest == NONE ? 0 : Math.max(0, clock.millis() - oldest) / 1000.0;
    }

}
