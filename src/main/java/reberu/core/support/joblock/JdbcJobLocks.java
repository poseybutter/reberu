package reberu.core.support.joblock;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.core.support.JobLocks;

class JdbcJobLocks implements JobLocks {

    static final int MAX_NAME_LENGTH = 60;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String owner = UUID.randomUUID().toString();

    JdbcJobLocks(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean runExclusively(String job, Duration lease, Runnable task) {
        if (job == null || job.isBlank() || job.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("주기 작업 이름 값 오류");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("주기 작업 잠금 기한 값 오류");
        }
        if (!acquire(job, lease)) {
            return false;
        }
        try {
            task.run();
        }
        finally {
            release(job);
        }
        return true;
    }

    private boolean acquire(String job, Duration lease) {
        LocalDateTime now = now();
        LocalDateTime until = now.plus(lease);
        int taken = jdbc.sql("""
                        UPDATE scheduled_job_lock SET locked_until = :until, locked_by = :owner
                        WHERE job_name = :job AND locked_until <= :now
                        """)
                .param("until", until)
                .param("owner", owner)
                .param("job", job)
                .param("now", now)
                .update();
        if (taken == 1) {
            return true;
        }
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM scheduled_job_lock WHERE job_name = :job")
                .param("job", job).query(Long.class).single() > 0;
        if (exists) {
            return false;
        }
        try {
            jdbc.sql("INSERT INTO scheduled_job_lock (job_name, locked_until, locked_by) VALUES (:job, :until, :owner)")
                    .param("job", job)
                    .param("until", until)
                    .param("owner", owner)
                    .update();
            return true;
        }
        catch (DuplicateKeyException raced) {
            return false;
        }
    }

    private void release(String job) {
        jdbc.sql("UPDATE scheduled_job_lock SET locked_until = :now WHERE job_name = :job AND locked_by = :owner")
                .param("now", now())
                .param("job", job)
                .param("owner", owner)
                .update();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

}
