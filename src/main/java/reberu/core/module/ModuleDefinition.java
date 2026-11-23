package reberu.core.module;

import java.util.Set;

public interface ModuleDefinition {

    String key();

    String name();

    String description();

    Scope scope();

    Source source();

    default Set<String> requires() {
        return Set.of();
    }

    default boolean enabledByDefault() {
        return false;
    }

    default Set<String> expSources() {
        return Set.of();
    }

    default Set<String> notificationKinds() {
        return Set.of();
    }

    default Set<String> realtimeEvents() {
        return Set.of();
    }

    default MigrationLocation migrationLocation() {
        return MigrationLocation.NONE;
    }

    enum Scope {
        TEAM,
        ORG
    }

    enum Source {
        BUNDLED,
        EXTENSION
    }

    enum MigrationLocation {
        NONE,
        CORE,
        COMPANY
    }

}
