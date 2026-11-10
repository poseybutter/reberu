package reberu.core.audit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.Uuids;

class JdbcActivityLogs implements ActivityLogs {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcActivityLogs(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOrg(UUID orgId, ActivityEntry entry) {
        insert("""
                INSERT INTO org_activity_log (id, org_id, actor_id, action, target_type, target_id,
                                              before_value, after_value, occurred_at)
                VALUES (:id, :owner, :actorId, :action, :targetType, :targetId, :before, :after, :occurredAt)
                """, Objects.requireNonNull(orgId, "조직 ID 없음"), entry);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTeam(UUID teamId, ActivityEntry entry) {
        insert("""
                INSERT INTO team_activity_log (id, team_id, actor_id, action, target_type, target_id,
                                               before_value, after_value, occurred_at)
                VALUES (:id, :owner, :actorId, :action, :targetType, :targetId, :before, :after, :occurredAt)
                """, Objects.requireNonNull(teamId, "팀 ID 없음"), entry);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean orgRecordedSince(UUID orgId, UUID actorId, String action, UUID targetId, Instant since) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM org_activity_log
                        WHERE org_id = :org AND actor_id = :actor AND action = :action AND target_id = :target
                          AND occurred_at >= :since
                        """)
                .param("org", Objects.requireNonNull(orgId, "조직 ID 없음").toString())
                .param("actor", Objects.requireNonNull(actorId, "행위자 ID 없음").toString())
                .param("action", Objects.requireNonNull(action, "행동 없음"))
                .param("target", Objects.requireNonNull(targetId, "대상 ID 없음").toString())
                .param("since", LocalDateTime.ofInstant(since.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC))
                .query(Long.class)
                .single() > 0;
    }

    private void insert(String sql, UUID owner, ActivityEntry entry) {
        Objects.requireNonNull(entry, "활동 기록 없음");
        jdbc.sql(sql)
                .param("id", Uuids.v7().toString())
                .param("owner", owner.toString())
                .param("actorId", entry.actorId() == null ? null : entry.actorId().toString())
                .param("action", entry.action())
                .param("targetType", entry.targetType())
                .param("targetId", entry.targetId().toString())
                .param("before", entry.beforeValue())
                .param("after", entry.afterValue())
                .param("occurredAt", LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC))
                .update();
    }

}
