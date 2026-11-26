package reberu.core.event.delivery;

import java.time.Clock;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.core.event.DomainEventListener;

@Configuration(proxyBeanMethods = false)
class EventMetricsConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor deliveryFailureAdvisor() {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(DomainEventListener.class),
                new DeliveryFailureMeter());
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return advisor;
    }

    @Bean
    EventBacklogMetrics eventBacklogMetrics(JdbcClient jdbc) {
        return new EventBacklogMetrics(jdbc, Clock.systemUTC());
    }

}
