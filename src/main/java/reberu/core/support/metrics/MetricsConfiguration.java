package reberu.core.support.metrics;

import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reberu.core.support.SecurityMetrics;

@Configuration(proxyBeanMethods = false)
class MetricsConfiguration {

    @Bean
    MeterBinder securityMeters() {
        return SecurityMetrics::bindTo;
    }

}
