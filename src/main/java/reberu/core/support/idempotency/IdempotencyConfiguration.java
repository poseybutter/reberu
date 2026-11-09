package reberu.core.support.idempotency;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.core.support.IdempotentRequests;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class IdempotencyConfiguration {

    @Bean
    IdempotentRequests idempotentRequests(JdbcClient jdbc, TransactionTemplate transactions, JsonMapper jsonMapper) {
        return new JdbcIdempotentRequests(jdbc, transactions, jsonMapper, Clock.systemUTC());
    }

    @Bean
    IdempotencyKeyCleanup idempotencyKeyCleanup(JdbcClient jdbc) {
        return new IdempotencyKeyCleanup(jdbc, Clock.systemUTC());
    }

}
