package reberu.core.support.idempotency;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

class IdempotencyKeyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanup.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    IdempotencyKeyCleanup(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
    void scheduled() {
        deleteExpired();
    }

    int deleteExpired() {
        int deleted = jdbc.sql("DELETE FROM api_idempotency_key WHERE expires_at <= :now")
                .param("now", LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC))
                .update();
        if (deleted > 0) {
            log.info("만료 중복 요청 키 정리: {}건", deleted);
        }
        return deleted;
    }

}
