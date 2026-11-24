package reberu.core.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.StaticApplicationContext;
import reberu.core.access.AccessLevel.Scope;
import reberu.core.access.ModuleSwitch;

class DomainEventConfigurationTest {

    @Test
    @DisplayName("ARCH-EVT-05: 팀 모듈 구독자가 팀 식별자 없는 이벤트를 받으면 시작 단계 검사가 실패")
    void startupCheckFailsOnUnscopedModuleListener() {
        ModuleSwitch modules = new ModuleSwitch() {

            @Override
            public Optional<Scope> installedScope(String moduleKey) {
                return Optional.of(Scope.TEAM);
            }

            @Override
            public Optional<String> owner(Class<?> type) {
                return type == UnscopedListener.class ? Optional.of("fake") : Optional.empty();
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
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerSingleton("unscopedListener", UnscopedListener.class);
        context.refresh();
        StaticListableBeanFactory switches = new StaticListableBeanFactory(Map.of("moduleSwitch", modules));

        assertThatThrownBy(() -> DomainEventConfiguration.domainEventListenerCheck(
                switches.getBeanProvider(ModuleSwitch.class)).onApplicationEvent(new ContextRefreshedEvent(context)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCH-EVT-05")
                .hasMessageContaining(UnscopedListener.class.getName());
        context.close();
    }

    record PlainEvent(UUID id) {
    }

    static class UnscopedListener {

        @DomainEventListener
        void on(PlainEvent event) {
        }

    }

}
