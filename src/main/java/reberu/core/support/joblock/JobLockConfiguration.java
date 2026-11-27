package reberu.core.support.joblock;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.core.support.JobLocks;

@Configuration(proxyBeanMethods = false)
public class JobLockConfiguration {

    @Bean
    JobLocks jobLocks(JdbcClient jdbc) {
        return new JdbcJobLocks(jdbc, Clock.systemUTC());
    }

}
