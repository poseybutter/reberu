package reberu.core.audit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

class ActivityLogCleanup {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogCleanup.class);

    static final int BATCH_SIZE = 500;

    private static final List<String> TABLES = List.of("org_activity_log", "team_activity_log");

    private final JdbcClient jdbc;
    private final JdbcClient batchJdbc;
    private final TransactionTemplate transactions;
    private final AuditProperties properties;
    private final Clock clock;

    ActivityLogCleanup(DataSource dataSource, TransactionTemplate transactions, AuditProperties properties,
            Clock clock) {
        JdbcTemplate batchTemplate = new JdbcTemplate(dataSource);
        batchTemplate.setMaxRows(BATCH_SIZE);
        this.jdbc = JdbcClient.create(dataSource);
        this.batchJdbc = JdbcClient.create(batchTemplate);
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
    void scheduled() {
        deleteExpired();
    }

    int deleteExpired() {
        Instant cutoff = clock.instant().minus(properties.retention()).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime threshold = LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        int total = 0;
        for (String table : TABLES) {
            total += deleteBefore(table, threshold);
        }
        return total;
    }

    private int deleteBefore(String table, LocalDateTime threshold) {
        int total = 0;
        try {
            int deleted;
            while ((deleted = deleteBatch(table, threshold)) > 0) {
                total += deleted;
            }
        }
        catch (RuntimeException exception) {
            log.error("활동 기록 정리 중단: {} occurred_at < {}Z, {}건 삭제 후 실패", table, threshold, total, exception);
            return total;
        }
        if (total > 0) {
            log.info("활동 기록 정리: {} occurred_at < {}Z, {}건", table, threshold, total);
        }
        return total;
    }

    int deleteBatch(String table, LocalDateTime threshold) {
        Integer deleted = transactions.execute(status -> {
            List<String> ids = batchJdbc.sql("SELECT id FROM " + table + " WHERE occurred_at < :threshold")
                    .param("threshold", threshold)
                    .query(String.class)
                    .list();
            if (ids.isEmpty()) {
                return 0;
            }
            return jdbc.sql("DELETE FROM " + table + " WHERE id IN (:ids) AND occurred_at < :threshold")
                    .param("ids", ids)
                    .param("threshold", threshold)
                    .update();
        });
        return deleted == null ? 0 : deleted;
    }

}
