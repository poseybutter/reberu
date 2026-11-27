package reberu.core.event.delivery;

import java.time.Duration;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;

class EventPublicationCleanup {

    private final CompletedEventPublications completed;
    private final Duration retention;

    EventPublicationCleanup(CompletedEventPublications completed, Duration retention) {
        this.completed = completed;
        this.retention = retention;
    }

    @Scheduled(cron = "0 47 3 * * *", zone = "UTC")
    void deleteExpired() {
        completed.deletePublicationsOlderThan(retention);
    }

}
