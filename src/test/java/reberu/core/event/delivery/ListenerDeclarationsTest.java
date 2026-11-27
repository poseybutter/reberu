package reberu.core.event.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reberu.core.access.AccessLevel.Scope;
import reberu.core.access.ModuleSwitch;
import reberu.core.event.DomainEventListener;
import reberu.core.event.OrgScoped;
import reberu.core.event.TeamScoped;

class ListenerDeclarationsTest {

    private static final ModuleSwitch MODULES = new ModuleSwitch() {

        @Override
        public Optional<Scope> installedScope(String moduleKey) {
            return switch (moduleKey) {
                case "fake" -> Optional.of(Scope.TEAM);
                case "fake-org" -> Optional.of(Scope.ORG);
                default -> Optional.empty();
            };
        }

        @Override
        public Optional<String> owner(Class<?> type) {
            if (type == TeamOwned.class) {
                return Optional.of("fake");
            }
            return type == OrgOwned.class ? Optional.of("fake-org") : Optional.empty();
        }

        @Override
        public boolean enabledInOrg(UUID orgId, String moduleKey) {
            return false;
        }

        @Override
        public boolean enabledInTeam(UUID teamId, String moduleKey) {
            return false;
        }

    };

    @Test
    @DisplayName("ARCH-EVT-05·11 위반 사례: 팀 모듈이 팀 식별자 없는 이벤트, 조직 모듈이 조직 식별자 없는 이벤트, 인자 둘인 구독 메서드, 값을 돌려주는 구독 메서드")
    void detectsListenerViolations() {
        assertThat(ListenerDeclarations.violations(List.of(TeamOwned.class, OrgOwned.class, Unowned.class), MODULES))
                .hasSize(4)
                .anySatisfy(v -> assertThat(v).isEqualTo("팀 모듈 구독자는 TeamScoped 이벤트만 받음(ARCH-EVT-05): "
                        + TeamOwned.class.getName() + ".onOrg"))
                .anySatisfy(v -> assertThat(v).isEqualTo("조직 모듈 구독자는 OrgScoped 이벤트만 받음(ARCH-EVT-05): "
                        + OrgOwned.class.getName() + ".onPlain"))
                .anySatisfy(v -> assertThat(v).isEqualTo("구독 메서드는 이벤트 하나만 받음: "
                        + Unowned.class.getName() + ".onTwo"))
                .anySatisfy(v -> assertThat(v).isEqualTo("구독 메서드 반환형은 void: "
                        + Unowned.class.getName() + ".onFuture"));
    }

    record TeamEvent(UUID orgId, UUID teamId) implements TeamScoped {
    }

    record OrgEvent(UUID orgId) implements OrgScoped {
    }

    record PlainEvent(UUID id) {
    }

    static class TeamOwned {

        @DomainEventListener
        void onTeam(TeamEvent event) {
        }

        @DomainEventListener
        void onOrg(OrgEvent event) {
        }

    }

    static class OrgOwned {

        @DomainEventListener
        void onOrg(OrgEvent event) {
        }

        @DomainEventListener
        void onTeam(TeamEvent event) {
        }

        @DomainEventListener
        void onPlain(PlainEvent event) {
        }

    }

    static class Unowned {

        @DomainEventListener
        void onPlain(PlainEvent event) {
        }

        @DomainEventListener
        void onTwo(PlainEvent event, PlainEvent other) {
        }

        @DomainEventListener
        CompletableFuture<Void> onFuture(PlainEvent event) {
            return CompletableFuture.completedFuture(null);
        }

    }

}
