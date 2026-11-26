package reberu.core.access.web;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import reberu.core.access.ModuleSwitch;

@Configuration(proxyBeanMethods = false)
class AccessConfiguration implements WebMvcConfigurer {

    private final MembershipDirectory directory;
    private final ModuleSwitch modules;
    private final Operators operators;

    AccessConfiguration(MembershipDirectory directory, ModuleSwitch modules,
            @Value("${reberu.operator.account-ids:}") List<String> operatorAccountIds) {
        this.directory = directory;
        this.modules = modules;
        this.operators = Operators.parse(operatorAccountIds);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new AccessInterceptor(new AccessDecider(directory, modules, Clock.systemUTC()), operators))
                .order(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AccessContextArgumentResolver());
    }

    @Bean
    static ApplicationListener<ContextRefreshedEvent> accessDeclarationCheck(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping,
            ObjectProvider<ModuleSwitch> modules) {
        return event -> {
            List<String> violations = AccessDeclarations.violations(handlerMapping.getObject().getHandlerMethods(),
                    modules.getObject());
            if (!violations.isEmpty()) {
                throw new IllegalStateException("엔드포인트 권한 선언 오류(SEC-AUTHZ-02):\n" + String.join("\n", violations));
            }
        };
    }

}
