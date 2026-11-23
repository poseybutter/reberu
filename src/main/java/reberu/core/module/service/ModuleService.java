package reberu.core.module.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.module.ModuleDefinition;
import reberu.core.module.ModuleDefinition.Scope;
import reberu.core.module.ModuleDefinition.Source;
import reberu.core.module.ModuleStates;
import reberu.core.module.OrgModuleChanged;
import reberu.core.module.TeamModuleChanged;
import reberu.core.module.service.ModuleStateStore.OrgRow;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;

public class ModuleService implements ModuleStates {

    static final String ALLOWED = "MODULE_ALLOWED";
    static final String REVOKED = "MODULE_REVOKED";
    static final String ENABLED = "MODULE_ENABLED";
    static final String DISABLED = "MODULE_DISABLED";

    private static final String ACTIVE = "ACTIVE";

    private final ModuleRegistry registry;
    private final ModuleStateStore store;
    private final ActivityLogs activityLogs;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ModuleService(ModuleRegistry registry, ModuleStateStore store, ActivityLogs activityLogs,
            ApplicationEventPublisher events, Clock clock) {
        this.registry = registry;
        this.store = store;
        this.activityLogs = activityLogs;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<OrgModuleView> listOrg(UUID orgId) {
        Map<String, OrgRow> rows = store.orgRows(orgId);
        Map<String, Integer> counts = store.enabledTeamCounts(orgId);
        return registry.all().stream().map(definition -> {
            OrgRow row = rows.get(definition.key());
            if (definition.scope() == Scope.ORG) {
                return new OrgModuleView(definition.key(), definition.name(), definition.description(),
                        definition.scope().name(), definition.source().name(), null, row != null && row.enabled(),
                        null, sorted(definition.requires()));
            }
            boolean allowed = allowed(definition, row);
            return new OrgModuleView(definition.key(), definition.name(), definition.description(),
                    definition.scope().name(), definition.source().name(), allowed, null,
                    allowed ? counts.getOrDefault(definition.key(), 0) : 0, sorted(definition.requires()));
        }).toList();
    }

    @Transactional(readOnly = true)
    public OrgImpact orgImpact(UUID orgId, String moduleKey) {
        ModuleDefinition definition = installed(moduleKey);
        Set<String> dependents = dependents(definition);
        Set<String> cascade = new TreeSet<>();
        if (definition.scope() == Scope.ORG) {
            Map<String, OrgRow> rows = store.orgRows(orgId);
            dependents.stream().filter(key -> rows.containsKey(key) && rows.get(key).enabled()).forEach(cascade::add);
            return new OrgImpact(null, List.copyOf(cascade));
        }
        if (!allowed(definition, store.orgRows(orgId).get(moduleKey))) {
            return new OrgImpact(0, List.of());
        }
        List<UUID> teams = store.teamsEnabling(orgId, moduleKey);
        for (UUID teamId : teams) {
            Map<String, Boolean> rows = store.teamRows(teamId);
            dependents.stream().filter(key -> Boolean.TRUE.equals(rows.get(key))).forEach(cascade::add);
        }
        return new OrgImpact(teams.size(), List.copyOf(cascade));
    }

    @Transactional
    public void setAllowed(UUID orgId, String moduleKey, boolean allowed, UUID actorId) {
        ModuleDefinition definition = installed(moduleKey);
        if (definition.scope() != Scope.TEAM) {
            throw notFound();
        }
        lockActiveOrg(orgId);
        if (allowed(definition, store.orgRows(orgId).get(moduleKey)) == allowed) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        Instant now = clock.instant();
        if (!allowed) {
            Set<String> dependents = dependents(definition);
            for (UUID teamId : store.teamsEnabling(orgId, moduleKey)) {
                Map<String, Boolean> rows = store.teamRows(teamId);
                List<String> off = new ArrayList<>(List.of(moduleKey));
                dependents.stream().filter(key -> Boolean.TRUE.equals(rows.get(key))).sorted().forEach(off::add);
                disableInTeam(orgId, teamId, off, actorId, now);
            }
        }
        store.setOrgAllowed(orgId, moduleKey, allowed, actorId, now);
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, allowed ? ALLOWED : REVOKED, "ORG", orgId, null,
                moduleKey));
    }

    @Transactional
    public void setOrgEnabled(UUID orgId, String moduleKey, boolean enabled, UUID actorId) {
        ModuleDefinition definition = installed(moduleKey);
        if (definition.scope() != Scope.ORG) {
            throw notFound();
        }
        lockActiveOrg(orgId);
        Map<String, OrgRow> rows = store.orgRows(orgId);
        if (isOn(rows.get(moduleKey)) == enabled) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        Instant now = clock.instant();
        List<String> changed = new ArrayList<>(List.of(moduleKey));
        if (enabled) {
            if (!definition.requires().stream().allMatch(key -> isOn(rows.get(key)))) {
                throw invalid("enabled", FieldReason.NOT_ALLOWED_VALUE);
            }
        }
        else {
            dependents(definition).stream().filter(key -> isOn(rows.get(key))).sorted().forEach(changed::add);
        }
        for (String key : changed) {
            store.setOrgEnabled(orgId, key, enabled, actorId, now);
            activityLogs.recordOrg(orgId, new ActivityEntry(actorId, enabled ? ENABLED : DISABLED, "ORG", orgId,
                    null, key));
        }
        events.publishEvent(new OrgModuleChanged(orgId, enabledOrgModules(orgId)));
    }

    @Transactional(readOnly = true)
    public List<TeamModuleView> listTeam(UUID orgId, UUID teamId) {
        Map<String, OrgRow> orgRows = store.orgRows(orgId);
        Map<String, Boolean> teamRows = store.teamRows(teamId);
        return registry.all().stream()
                .filter(definition -> definition.scope() == Scope.TEAM)
                .filter(definition -> allowed(definition, orgRows.get(definition.key())))
                .map(definition -> new TeamModuleView(definition.key(), definition.name(), definition.description(),
                        definition.source().name(), Boolean.TRUE.equals(teamRows.get(definition.key())),
                        sorted(definition.requires())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamImpact teamImpact(UUID orgId, UUID teamId, String moduleKey) {
        ModuleDefinition definition = allowedTeamModule(orgId, moduleKey);
        Map<String, Boolean> rows = store.teamRows(teamId);
        List<String> cascade = dependents(definition).stream()
                .filter(key -> Boolean.TRUE.equals(rows.get(key)))
                .sorted()
                .toList();
        List<String> required = requiredClosure(definition).stream()
                .filter(key -> !Boolean.TRUE.equals(rows.get(key)))
                .sorted()
                .toList();
        return new TeamImpact(cascade, required);
    }

    @Transactional
    public void setTeamEnabled(UUID orgId, UUID teamId, String moduleKey, boolean enabled, Boolean confirmCascade,
            UUID actorId) {
        installed(moduleKey);
        lockActiveOrg(orgId);
        String teamState = store.lockTeam(orgId, teamId).orElseThrow(ModuleService::notFound);
        if (!ACTIVE.equals(teamState)) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        ModuleDefinition definition = allowedTeamModule(orgId, moduleKey);
        Map<String, Boolean> rows = store.teamRows(teamId);
        if (Boolean.TRUE.equals(rows.get(moduleKey)) == enabled) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        Instant now = clock.instant();
        if (enabled) {
            if (!definition.requires().stream().allMatch(key -> Boolean.TRUE.equals(rows.get(key)))) {
                throw invalid("enabled", FieldReason.NOT_ALLOWED_VALUE);
            }
            store.setTeamEnabled(teamId, moduleKey, true, actorId, now);
            activityLogs.recordTeam(teamId, new ActivityEntry(actorId, ENABLED, "TEAM", teamId, null, moduleKey));
            events.publishEvent(new TeamModuleChanged(orgId, teamId, enabledTeamModules(teamId)));
            return;
        }
        List<String> cascade = dependents(definition).stream()
                .filter(key -> Boolean.TRUE.equals(rows.get(key)))
                .sorted()
                .toList();
        if (!cascade.isEmpty() && !Boolean.TRUE.equals(confirmCascade)) {
            throw invalid("confirmCascade", FieldReason.REQUIRED);
        }
        List<String> off = new ArrayList<>(List.of(moduleKey));
        off.addAll(cascade);
        disableInTeam(orgId, teamId, off, actorId, now);
    }

    @Override
    @Transactional
    public void enableForNewTeam(UUID orgId, UUID teamId, List<String> jobTypeModules, UUID actorId, Instant at) {
        Map<String, OrgRow> orgRows = store.orgRows(orgId);
        List<ModuleDefinition> candidates = jobTypeModules.isEmpty()
                ? registry.all().stream().filter(ModuleDefinition::enabledByDefault).toList()
                : jobTypeModules.stream().distinct().flatMap(key -> registry.find(key).stream()).toList();
        List<ModuleDefinition> allowed = candidates.stream()
                .filter(definition -> definition.scope() == Scope.TEAM)
                .filter(definition -> allowed(definition, orgRows.get(definition.key())))
                .toList();
        Set<String> enabled = new LinkedHashSet<>();
        boolean added = true;
        while (added) {
            added = false;
            for (ModuleDefinition definition : allowed) {
                if (!enabled.contains(definition.key()) && enabled.containsAll(definition.requires())) {
                    enabled.add(definition.key());
                    added = true;
                }
            }
        }
        if (enabled.isEmpty()) {
            return;
        }
        for (String key : enabled) {
            store.setTeamEnabled(teamId, key, true, actorId, at);
            activityLogs.recordTeam(teamId, new ActivityEntry(actorId, ENABLED, "TEAM", teamId, null, key));
        }
        events.publishEvent(new TeamModuleChanged(orgId, teamId, enabledTeamModules(teamId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> enabledTeamModules(UUID teamId) {
        Map<String, Boolean> rows = store.teamRows(teamId);
        if (rows.isEmpty()) {
            return List.of();
        }
        UUID orgId = store.orgOfTeam(teamId).orElse(null);
        if (orgId == null) {
            return List.of();
        }
        Map<String, OrgRow> orgRows = store.orgRows(orgId);
        return registry.all().stream()
                .filter(definition -> definition.scope() == Scope.TEAM)
                .filter(definition -> Boolean.TRUE.equals(rows.get(definition.key())))
                .filter(definition -> allowed(definition, orgRows.get(definition.key())))
                .map(ModuleDefinition::key)
                .toList();
    }

    private List<String> enabledOrgModules(UUID orgId) {
        Map<String, OrgRow> rows = store.orgRows(orgId);
        return registry.all().stream()
                .filter(definition -> definition.scope() == Scope.ORG)
                .filter(definition -> isOn(rows.get(definition.key())))
                .map(ModuleDefinition::key)
                .toList();
    }

    private void disableInTeam(UUID orgId, UUID teamId, List<String> keys, UUID actorId, Instant at) {
        for (String key : keys) {
            store.setTeamEnabled(teamId, key, false, actorId, at);
            activityLogs.recordTeam(teamId, new ActivityEntry(actorId, DISABLED, "TEAM", teamId, null, key));
        }
        events.publishEvent(new TeamModuleChanged(orgId, teamId, enabledTeamModules(teamId)));
    }

    private ModuleDefinition allowedTeamModule(UUID orgId, String moduleKey) {
        ModuleDefinition definition = installed(moduleKey);
        if (definition.scope() != Scope.TEAM || !allowed(definition, store.orgRows(orgId).get(moduleKey))) {
            throw notFound();
        }
        return definition;
    }

    private void lockActiveOrg(UUID orgId) {
        String state = store.lockOrg(orgId).orElseThrow(ModuleService::notFound);
        if (!ACTIVE.equals(state)) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
    }

    private ModuleDefinition installed(String moduleKey) {
        return registry.find(moduleKey).orElseThrow(ModuleService::notFound);
    }

    private Set<String> dependents(ModuleDefinition definition) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(definition.key()));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (ModuleDefinition other : registry.all()) {
                if (other.requires().contains(current) && found.add(other.key())) {
                    queue.add(other.key());
                }
            }
        }
        return found;
    }

    private Set<String> requiredClosure(ModuleDefinition definition) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(definition.requires());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (found.add(current)) {
                registry.find(current).ifPresent(required -> queue.addAll(required.requires()));
            }
        }
        return found;
    }

    private static boolean allowed(ModuleDefinition definition, OrgRow row) {
        return row == null ? definition.source() == Source.BUNDLED : row.allowed();
    }

    private static boolean isOn(OrgRow row) {
        return row != null && row.enabled();
    }

    private static List<String> sorted(Set<String> keys) {
        return keys.stream().sorted().toList();
    }

    private static ReberuException invalid(String field, FieldReason reason) {
        return new ReberuException(ErrorCode.INVALID_REQUEST, List.of(new FieldViolation(field, reason)));
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    public record OrgModuleView(String key, String name, String description, String scope, String source,
            Boolean allowed, Boolean enabled, Integer enabledTeamCount, List<String> requires) {
    }

    public record OrgImpact(Integer affectedTeamCount, List<String> cascade) {
    }

    public record TeamModuleView(String key, String name, String description, String source, boolean enabled,
            List<String> requires) {
    }

    public record TeamImpact(List<String> cascade, List<String> requiredFirst) {
    }

}
