package reberu.core.event.delivery;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.EnableScheduling;
import reberu.core.support.JobLocks;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(EventDeliveryProperties.class)
public class EventRetryConfiguration {

    @Bean
    EventRetry eventRetry(IncompleteEventPublications publications, EventDeliveryProperties properties,
            JobLocks locks) {
        return new EventRetry(publications, properties.retry().intervals(), Clock.systemUTC(), locks);
    }

    @Bean
    EventPublicationCleanup eventPublicationCleanup(CompletedEventPublications completed,
            EventDeliveryProperties properties) {
        return new EventPublicationCleanup(completed, properties.retention());
    }

}
