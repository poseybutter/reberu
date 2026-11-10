package reberu.core.auth.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import reberu.core.support.ErrorResponseWriter;
import reberu.core.support.RateLimits;

@Configuration(proxyBeanMethods = false)
class LoginRateLimitConfiguration {

    static final String CALLBACK_PATH = "/login/oauth2/code/google";

    @Bean
    FilterRegistrationBean<LoginRateLimitFilter> loginRateLimitFilter(RateLimits rateLimits,
            ErrorResponseWriter errorWriter) {
        FilterRegistrationBean<LoginRateLimitFilter> registration =
                new FilterRegistrationBean<>(new LoginRateLimitFilter(rateLimits, errorWriter));
        registration.addUrlPatterns(LoginRequestResolver.AUTHORIZATION_BASE_URI + "/google", CALLBACK_PATH);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }

}
