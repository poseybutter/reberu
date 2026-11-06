package reberu.core.auth.security;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class RecentAuthConfiguration implements WebMvcConfigurer {

    private final Duration window;

    RecentAuthConfiguration(@Value("${reberu.session.recent-auth-window}") Duration window) {
        this.window = window;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RecentAuthInterceptor(window, Clock.systemUTC()))
                .order(Ordered.HIGHEST_PRECEDENCE);
    }

}
