package reberu.core.event;

import java.util.ArrayList;
import java.util.List;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import reberu.core.access.ModuleSwitch;
import reberu.core.event.delivery.ListenerDeclarations;
import reberu.core.event.delivery.ModuleDeliveryGuard;

@Configuration(proxyBeanMethods = false)
@EnableAsync
public class DomainEventConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor moduleDeliveryAdvisor(ObjectProvider<ModuleSwitch> modules) {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(DomainEventListener.class),
                new ModuleDeliveryGuard(modules));
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE);
        return advisor;
    }

    @Bean
    static ApplicationListener<ContextRefreshedEvent> domainEventListenerCheck(ObjectProvider<ModuleSwitch> modules) {
        return event -> {
            ModuleSwitch switches = modules.getIfAvailable();
            if (switches == null) {
                return;
            }
            List<Class<?>> types = new ArrayList<>();
            for (String name : event.getApplicationContext().getBeanDefinitionNames()) {
                Class<?> type = event.getApplicationContext().getType(name, false);
                if (type != null) {
                    types.add(type);
                }
            }
            List<String> violations = ListenerDeclarations.violations(types, switches);
            if (!violations.isEmpty()) {
                throw new IllegalStateException("도메인 이벤트 구독 선언 오류(ARCH-EVT-05):\n" + String.join("\n", violations));
            }
        };
    }

}
