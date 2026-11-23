package fakemodule;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reberu.core.event.DomainEventListener;
import reberu.core.event.OrgScoped;
import reberu.core.event.TeamScoped;

public final class FakeEvents {

    public static final Map<String, Set<UUID>> RECEIVED = new ConcurrentHashMap<>();

    private FakeEvents() {
    }

    public static void received(String listener, UUID id) {
        RECEIVED.computeIfAbsent(listener, key -> ConcurrentHashMap.newKeySet()).add(id);
    }

    public static boolean wasReceived(String listener, UUID id) {
        return RECEIVED.getOrDefault(listener, Set.of()).contains(id);
    }

    public record TeamPinged(UUID orgId, UUID teamId, UUID id) implements TeamScoped {
    }

    public record OrgPinged(UUID orgId, UUID id) implements OrgScoped {
    }

    public static class UnownedListener {

        public static final String NAME = "unowned";

        @DomainEventListener
        void on(TeamPinged event) {
            received(NAME, event.id());
        }

    }

}
