package reberu.core.module.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import reberu.core.access.AccessLevel.Scope;
import reberu.core.access.ModuleSwitch;
import reberu.core.module.ModuleDefinition;
import reberu.core.module.ModuleDefinition.Source;

class JdbcModuleSwitch implements ModuleSwitch {

    private final ModuleRegistry registry;
    private final JdbcClient jdbc;

    JdbcModuleSwitch(ModuleRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Scope> installedScope(String moduleKey) {
        return registry.find(moduleKey).map(definition -> switch (definition.scope()) {
            case TEAM -> Scope.TEAM;
            case ORG -> Scope.ORG;
        });
    }

    @Override
    public Optional<String> owner(Class<?> type) {
        return registry.owner(type);
    }

    @Override
    public boolean enabledInOrg(UUID orgId, String moduleKey) {
        Optional<ModuleDefinition> definition = registry.find(moduleKey);
        if (definition.isEmpty() || definition.get().scope() != ModuleDefinition.Scope.ORG) {
            return false;
        }
        return jdbc.sql("SELECT enabled FROM org_module_state WHERE org_id = :org AND module_key = :key")
                .param("org", orgId.toString())
                .param("key", moduleKey)
                .query((rs, rowNum) -> rs.getBoolean("enabled"))
                .optional()
                .orElse(false);
    }

    @Override
    public boolean enabledInTeam(UUID teamId, String moduleKey) {
        Optional<ModuleDefinition> definition = registry.find(moduleKey);
        if (definition.isEmpty() || definition.get().scope() != ModuleDefinition.Scope.TEAM) {
            return false;
        }
        boolean allowedByDefault = definition.get().source() == Source.BUNDLED;
        return jdbc.sql("""
                        SELECT tms.enabled AS team_enabled, oms.allowed AS org_allowed
                        FROM team t
                        LEFT JOIN team_module_state tms ON tms.team_id = t.id AND tms.module_key = :key
                        LEFT JOIN org_module_state oms ON oms.org_id = t.org_id AND oms.module_key = :key
                        WHERE t.id = :team
                        """)
                .param("team", teamId.toString())
                .param("key", moduleKey)
                .query((rs, rowNum) -> {
                    Boolean allowed = flag(rs, "org_allowed");
                    return Boolean.TRUE.equals(flag(rs, "team_enabled"))
                            && (allowed == null ? allowedByDefault : allowed);
                })
                .optional()
                .orElse(false);
    }

    private static Boolean flag(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

}
