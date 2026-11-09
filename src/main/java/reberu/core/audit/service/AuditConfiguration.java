package reberu.core.audit.service;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.core.audit.ActivityLogs;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditProperties.class)
class AuditConfiguration {

    @Bean
    ActivityLogs activityLogs(JdbcClient jdbc) {
        return new JdbcActivityLogs(jdbc, Clock.systemUTC());
    }

    @Bean
    ActivityLogCleanup activityLogCleanup(DataSource dataSource, TransactionTemplate transactions,
            AuditProperties properties) {
        return new ActivityLogCleanup(dataSource, transactions, properties, Clock.systemUTC());
    }

}
