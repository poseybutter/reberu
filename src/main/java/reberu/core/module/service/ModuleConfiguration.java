package reberu.core.module.service;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.core.access.ModuleSwitch;
import reberu.core.audit.ActivityLogs;
import reberu.core.module.ModuleDefinition;

@Configuration(proxyBeanMethods = false)
class ModuleConfiguration {

    @Bean
    ModuleRegistry moduleRegistry(ObjectProvider<ModuleDefinition> definitions) {
        return new ModuleRegistry(List.copyOf(definitions.orderedStream().toList()));
    }

    @Bean
    ModuleSwitch moduleSwitch(ModuleRegistry registry, JdbcClient jdbc) {
        return new JdbcModuleSwitch(registry, jdbc);
    }

    @Bean
    ModuleService moduleService(ModuleRegistry registry, JdbcClient jdbc, ActivityLogs activityLogs,
            ApplicationEventPublisher events) {
        return new ModuleService(registry, new ModuleStateStore(jdbc), activityLogs, events, Clock.systemUTC());
    }

}
