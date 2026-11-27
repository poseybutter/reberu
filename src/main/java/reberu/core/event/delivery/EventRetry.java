package reberu.core.event.delivery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.EventPublication.Status;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import reberu.core.support.JobLocks;

class EventRetry implements MeterBinder {

    static final String EXHAUSTED = "reberu.events.exhausted";
    static final String JOB = "event-retry";
    static final Duration LEASE = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(EventRetry.class);

    private final IncompleteEventPublications publications;
    private final List<Duration> intervals;
    private final Clock clock;
    private final JobLocks locks;
    private final Set<UUID> reported = ConcurrentHashMap.newKeySet();
    private final AtomicLong exhausted = new AtomicLong();

    EventRetry(IncompleteEventPublications publications, List<Duration> intervals, Clock clock, JobLocks locks) {
        this.publications = publications;
        this.intervals = List.copyOf(intervals);
        this.clock = clock;
        this.locks = locks;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(EXHAUSTED, exhausted, AtomicLong::doubleValue).register(registry);
    }

    @Scheduled(initialDelayString = "${reberu.events.retry.check-interval}",
            fixedDelayString = "${reberu.events.retry.check-interval}")
    void scheduled() {
        runOnce();
    }

    int runOnce() {
        AtomicInteger due = new AtomicInteger();
        locks.runExclusively(JOB, LEASE, () -> due.set(resubmitDue()));
        return due.get();
    }

    private int resubmitDue() {
        Instant now = clock.instant();
        Set<UUID> gaveUp = new HashSet<>();
        AtomicInteger due = new AtomicInteger();
        publications.resubmitIncompletePublications(publication -> {
            if (publication.getStatus() != Status.FAILED) {
                return false;
            }
            int retries = Math.max(0, publication.getCompletionAttempts() - 1);
            if (retries >= intervals.size()) {
                gaveUp.add(publication.getIdentifier());
                report(publication);
                return false;
            }
            if (publication.getLastPublicationDate().plus(intervals.get(retries)).isAfter(now)) {
                return false;
            }
            due.incrementAndGet();
            return true;
        });
        reported.retainAll(gaveUp);
        exhausted.set(gaveUp.size());
        return due.get();
    }

    private void report(EventPublication publication) {
        if (!reported.add(publication.getIdentifier())) {
            return;
        }
        log.error("이벤트 전달 포기: id={}, event={}, attempts={}", publication.getIdentifier(),
                publication.getEvent().getClass().getName(), publication.getCompletionAttempts());
    }

}
