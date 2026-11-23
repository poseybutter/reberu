package reberu.core.module.service;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.core.access.ModuleSwitch;
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

}
