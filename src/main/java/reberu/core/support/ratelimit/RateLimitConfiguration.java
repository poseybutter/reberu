package reberu.core.support.ratelimit;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import reberu.core.support.RateLimits;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfiguration implements WebMvcConfigurer {

    private final ObjectProvider<RateLimits> rateLimits;

    RateLimitConfiguration(ObjectProvider<RateLimits> rateLimits) {
        this.rateLimits = rateLimits;
    }

    @Bean
    SlidingWindowRateLimits rateLimits(RateLimitProperties properties) {
        return new SlidingWindowRateLimits(properties, Clock.systemUTC());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(rateLimits.getObject())).order(Ordered.HIGHEST_PRECEDENCE + 5);
    }

}
