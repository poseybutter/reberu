package reberu.core.support.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class WebSupportConfiguration {

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<AppVersionHeaderFilter> appVersionHeaderFilter(
            @Value("${reberu.app.version}") String version,
            @Value("${reberu.app.min-version}") String minVersion) {
        FilterRegistrationBean<AppVersionHeaderFilter> registration =
                new FilterRegistrationBean<>(new AppVersionHeaderFilter(version, minVersion));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

}
