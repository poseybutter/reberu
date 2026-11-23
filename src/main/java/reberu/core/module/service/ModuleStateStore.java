package reberu.core.module.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.core.support.Uuids;

class ModuleStateStore {

    private final JdbcClient jdbc;

    ModuleStateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> lockOrg(UUID orgId) {
        return jdbc.sql("SELECT state FROM organization WHERE id = :org FOR UPDATE")
                .param("org", orgId.toString())
                .query(String.class)
                .optional();
    }

    Optional<String> lockTeam(UUID orgId, UUID teamId) {
        return jdbc.sql("SELECT state FROM team WHERE id = :team AND org_id = :org FOR UPDATE")
                .param("team", teamId.toString())
                .param("org", orgId.toString())
                .query(String.class)
                .optional();
    }

    Optional<UUID> orgOfTeam(UUID teamId) {
        return jdbc.sql("SELECT org_id FROM team WHERE id = :team")
                .param("team", teamId.toString())
                .query((rs, rowNum) -> UUID.fromString(rs.getString(1)))
                .optional();
    }

    Map<String, OrgRow> orgRows(UUID orgId) {
        Map<String, OrgRow> rows = new HashMap<>();
        jdbc.sql("SELECT module_key, allowed, enabled FROM org_module_state WHERE org_id = :org")
                .param("org", orgId.toString())
                .query((rs, rowNum) -> rows.put(rs.getString("module_key"),
                        new OrgRow(rs.getBoolean("allowed"), rs.getBoolean("enabled"))))
                .list();
        return rows;
    }

    Map<String, Boolean> teamRows(UUID teamId) {
        Map<String, Boolean> rows = new HashMap<>();
        jdbc.sql("SELECT module_key, enabled FROM team_module_state WHERE team_id = :team")
                .param("team", teamId.toString())
                .query((rs, rowNum) -> rows.put(rs.getString("module_key"), rs.getBoolean("enabled")))
                .list();
        return rows;
    }

    List<UUID> teamsEnabling(UUID orgId, String moduleKey) {
        return jdbc.sql("""
                        SELECT t.id FROM team t
                        JOIN team_module_state tms ON tms.team_id = t.id
                        WHERE t.org_id = :org AND tms.module_key = :key AND tms.enabled = :enabled
                        ORDER BY t.id
                        """)
                .param("org", orgId.toString())
                .param("key", moduleKey)
                .param("enabled", true)
                .query((rs, rowNum) -> UUID.fromString(rs.getString(1)))
                .list();
    }

    Map<String, Integer> enabledTeamCounts(UUID orgId) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT tms.module_key, COUNT(*) AS teams FROM team t
                        JOIN team_module_state tms ON tms.team_id = t.id
                        WHERE t.org_id = :org AND tms.enabled = :enabled
                        GROUP BY tms.module_key
                        """)
                .param("org", orgId.toString())
                .param("enabled", true)
                .query((rs, rowNum) -> counts.put(rs.getString("module_key"), rs.getInt("teams")))
                .list();
        return counts;
    }

    void setOrgAllowed(UUID orgId, String moduleKey, boolean allowed, UUID actorId, Instant at) {
        int updated = jdbc.sql("""
                        UPDATE org_module_state SET allowed = :value, changed_by = :actor, changed_at = :at
                        WHERE org_id = :org AND module_key = :key
                        """)
                .params(orgParams(orgId, moduleKey, allowed, actorId, at))
                .update();
        if (updated == 0) {
            insertOrg(orgId, moduleKey, allowed, false, actorId, at);
        }
    }

    void setOrgEnabled(UUID orgId, String moduleKey, boolean enabled, UUID actorId, Instant at) {
        int updated = jdbc.sql("""
                        UPDATE org_module_state SET enabled = :value, changed_by = :actor, changed_at = :at
                        WHERE org_id = :org AND module_key = :key
                        """)
                .params(orgParams(orgId, moduleKey, enabled, actorId, at))
                .update();
        if (updated == 0) {
            insertOrg(orgId, moduleKey, false, enabled, actorId, at);
        }
    }

    void setTeamEnabled(UUID teamId, String moduleKey, boolean enabled, UUID actorId, Instant at) {
        int updated = jdbc.sql("""
                        UPDATE team_module_state SET enabled = :enabled, changed_by = :actor, changed_at = :at
                        WHERE team_id = :team AND module_key = :key
                        """)
                .param("enabled", enabled)
                .param("actor", actorId == null ? null : actorId.toString())
                .param("at", timestamp(at))
                .param("team", teamId.toString())
                .param("key", moduleKey)
                .update();
        if (updated == 0) {
            jdbc.sql("""
                            INSERT INTO team_module_state (id, team_id, module_key, enabled, changed_by, changed_at)
                            VALUES (:id, :team, :key, :enabled, :actor, :at)
                            """)
                    .param("id", Uuids.v7().toString())
                    .param("team", teamId.toString())
                    .param("key", moduleKey)
                    .param("enabled", enabled)
                    .param("actor", actorId == null ? null : actorId.toString())
                    .param("at", timestamp(at))
                    .update();
        }
    }

    private void insertOrg(UUID orgId, String moduleKey, boolean allowed, boolean enabled, UUID actorId,
            Instant at) {
        jdbc.sql("""
                        INSERT INTO org_module_state (id, org_id, module_key, allowed, enabled, changed_by, changed_at)
                        VALUES (:id, :org, :key, :allowed, :enabled, :actor, :at)
                        """)
                .param("id", Uuids.v7().toString())
                .param("org", orgId.toString())
                .param("key", moduleKey)
                .param("allowed", allowed)
                .param("enabled", enabled)
                .param("actor", actorId == null ? null : actorId.toString())
                .param("at", timestamp(at))
                .update();
    }

    private static Map<String, Object> orgParams(UUID orgId, String moduleKey, boolean value, UUID actorId,
            Instant at) {
        Map<String, Object> params = new HashMap<>();
        params.put("value", value);
        params.put("actor", actorId == null ? null : actorId.toString());
        params.put("at", timestamp(at));
        params.put("org", orgId.toString());
        params.put("key", moduleKey);
        return params;
    }

    private static LocalDateTime timestamp(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    record OrgRow(boolean allowed, boolean enabled) {
    }

}
