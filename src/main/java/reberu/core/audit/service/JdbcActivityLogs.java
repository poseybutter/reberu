package reberu.core.audit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.audit.ActivityRecord;
import reberu.core.audit.OrgActivityRecord;
import reberu.core.audit.OrgActivitySearch;
import reberu.core.support.Uuids;

class JdbcActivityLogs implements ActivityLogs {

    static final int MAX_RECENT = 50;
    static final int MAX_SEARCH = 101;

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

    @Override
    @Transactional(readOnly = true)
    public List<ActivityRecord> recentOrg(UUID orgId, int limit) {
        return recent("org_activity_log", "org_id", Objects.requireNonNull(orgId, "조직 ID 없음"), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityRecord> recentTeam(UUID teamId, int limit) {
        return recent("team_activity_log", "team_id", Objects.requireNonNull(teamId, "팀 ID 없음"), limit);
    }

    private List<ActivityRecord> recent(String table, String ownerColumn, UUID owner, int limit) {
        if (limit < 1 || limit > MAX_RECENT) {
            throw new IllegalArgumentException("최근 활동 개수 범위 오류(1~" + MAX_RECENT + ")");
        }
        return jdbc.sql("SELECT id, occurred_at, actor_id, action, target_type, target_id, before_value, after_value"
                        + " FROM " + table + " WHERE " + ownerColumn + " = :owner"
                        + " ORDER BY occurred_at DESC, id DESC LIMIT :limit")
                .param("owner", owner.toString())
                .param("limit", limit)
                .query((rs, rowNum) -> {
                    String actor = rs.getString("actor_id");
                    return new ActivityRecord(UUID.fromString(rs.getString("id")),
                            rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                            actor == null ? null : UUID.fromString(actor), rs.getString("action"),
                            rs.getString("target_type"), UUID.fromString(rs.getString("target_id")),
                            rs.getString("before_value"), rs.getString("after_value"));
                })
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgActivityRecord> searchOrg(OrgActivitySearch search) {
        if (search.limit() < 1 || search.limit() > MAX_SEARCH) {
            throw new IllegalArgumentException("활동 기록 개수 범위 오류(1~" + MAX_SEARCH + ")");
        }
        StringBuilder filters = new StringBuilder();
        if (search.from() != null) {
            filters.append(" AND occurred_at >= :from");
        }
        if (search.to() != null) {
            filters.append(" AND occurred_at < :to");
        }
        if (search.actorId() != null) {
            filters.append(" AND actor_id = :actor");
        }
        if (search.action() != null) {
            filters.append(" AND action = :action");
        }
        if (search.afterAt() != null) {
            filters.append(" AND (occurred_at < :afterAt OR (occurred_at = :afterAt AND id < :afterId))");
        }
        String page = " ORDER BY occurred_at DESC, id DESC LIMIT :limit";
        String sql = """
                (SELECT id, CAST(NULL AS CHAR(36)) AS team_id, occurred_at, actor_id, action, target_type,
                        target_id, before_value, after_value
                 FROM org_activity_log
                 WHERE org_id = :org""" + (search.orgTargetId() == null ? "" : " AND target_id = :orgTarget") + filters
                + page + ")";
        if (!search.teamIds().isEmpty()) {
            sql += """
                     UNION ALL
                    (SELECT id, team_id, occurred_at, actor_id, action, target_type,
                            target_id, before_value, after_value
                     FROM team_activity_log
                     WHERE team_id IN (:teams)""" + filters + page + ")";
        }
        JdbcClient.StatementSpec query = jdbc.sql("SELECT * FROM (" + sql
                        + ") l ORDER BY l.occurred_at DESC, l.id DESC LIMIT :limit")
                .param("org", search.orgId().toString())
                .param("limit", search.limit());
        if (search.orgTargetId() != null) {
            query = query.param("orgTarget", search.orgTargetId().toString());
        }
        if (!search.teamIds().isEmpty()) {
            query = query.param("teams", search.teamIds().stream().map(UUID::toString).toList());
        }
        if (search.from() != null) {
            query = query.param("from", timestamp(search.from()));
        }
        if (search.to() != null) {
            query = query.param("to", timestamp(search.to()));
        }
        if (search.actorId() != null) {
            query = query.param("actor", search.actorId().toString());
        }
        if (search.action() != null) {
            query = query.param("action", search.action());
        }
        if (search.afterAt() != null) {
            query = query.param("afterAt", timestamp(search.afterAt())).param("afterId", search.afterId().toString());
        }
        return query.query((rs, rowNum) -> {
            String team = rs.getString("team_id");
            String actor = rs.getString("actor_id");
            return new OrgActivityRecord(UUID.fromString(rs.getString("id")),
                    team == null ? null : UUID.fromString(team.strip()),
                    rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    actor == null ? null : UUID.fromString(actor), rs.getString("action"),
                    rs.getString("target_type"), UUID.fromString(rs.getString("target_id")),
                    rs.getString("before_value"), rs.getString("after_value"));
        }).list();
    }

    private static LocalDateTime timestamp(Instant at) {
        return LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
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
