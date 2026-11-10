package reberu.core.access.web;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import reberu.core.access.MembershipDirectory;

@Configuration(proxyBeanMethods = false)
class AccessConfiguration implements WebMvcConfigurer {

    private final MembershipDirectory directory;

    AccessConfiguration(MembershipDirectory directory) {
        this.directory = directory;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessInterceptor(new AccessDecider(directory, Clock.systemUTC())))
                .order(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AccessContextArgumentResolver());
    }

    @Bean
    static ApplicationListener<ContextRefreshedEvent> accessDeclarationCheck(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        return event -> {
            List<String> violations = AccessDeclarations.violations(handlerMapping.getObject().getHandlerMethods());
            if (!violations.isEmpty()) {
                throw new IllegalStateException("엔드포인트 권한 선언 오류(SEC-AUTHZ-02):\n" + String.join("\n", violations));
            }
        };
    }

}
