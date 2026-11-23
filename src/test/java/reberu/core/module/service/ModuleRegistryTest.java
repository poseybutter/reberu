package reberu.core.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fakemodule.ext.FakeExtensionModule;
import fakemodule.org.FakeOrgModule;
import fakemodule.team.FakeTeamModule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reberu.core.module.ModuleDefinition;
import reberu.core.module.ModuleDefinition.MigrationLocation;
import reberu.core.module.ModuleDefinition.Scope;
import reberu.core.module.ModuleDefinition.Source;

class ModuleRegistryTest {

    @Test
    @DisplayName("ARCH-MOD-03: 올바른 정의는 key 순서로 고정되고, 패키지로 모듈 주인을 찾음")
    void registersValidModules() {
        ModuleRegistry registry = new ModuleRegistry(
                List.of(new FakeTeamModule(), new FakeOrgModule(), new FakeExtensionModule()));

        assertThat(registry.all()).extracting(ModuleDefinition::key).containsExactly("fake", "fake-ext", "fake-org");
        assertThat(registry.find("fake-org")).isPresent();
        assertThat(registry.find("gone")).isEmpty();
        assertThat(registry.owner(FakeTeamModule.Api.class)).contains("fake");
        assertThat(registry.owner(FakeOrgModule.Api.class)).contains("fake-org");
        assertThat(registry.owner(String.class)).isEmpty();
    }

    @Test
    @DisplayName("ARCH-MOD-01: 검증에 실패하면 사유를 모아 시작 실패")
    void failsWithReasons() {
        assertThatThrownBy(() -> new ModuleRegistry(List.of(new FakeTeamModule(),
                new TestModule("fake", Scope.TEAM, Source.BUNDLED))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCH-MOD-01")
                .hasMessageContaining("모듈 key 중복(V1): fake");
    }

    @Test
    @DisplayName("V1·key 형식·이름: 중복 key, 형식이 틀린 key, 이름·설명 없음")
    void rejectsKeysAndNames() {
        TestModule unnamed = new TestModule("unnamed", Scope.TEAM, Source.BUNDLED);
        unnamed.name = " ";

        assertThat(ModuleRegistry.violations(List.of(new TestModule("Bad_Key", Scope.TEAM, Source.BUNDLED),
                new TestModule("a".repeat(41), Scope.TEAM, Source.BUNDLED), unnamed,
                new TestModule("same", Scope.TEAM, Source.BUNDLED), new TestModule("same", Scope.ORG, Source.BUNDLED))))
                .anySatisfy(v -> assertThat(v).startsWith("모듈 key 형식 오류: Bad_Key"))
                .anySatisfy(v -> assertThat(v).startsWith("모듈 key 형식 오류: " + "a".repeat(41)))
                .anySatisfy(v -> assertThat(v).startsWith("모듈 이름·설명 없음: unnamed"))
                .anySatisfy(v -> assertThat(v).startsWith("모듈 key 중복(V1): same"));
    }

    @Test
    @DisplayName("V2: 필요한 모듈이 없거나 자기 자신·다른 범위·순환이면 실패")
    void rejectsRequirements() {
        assertThat(ModuleRegistry.violations(List.of(
                new TestModule("missing", Scope.TEAM, Source.BUNDLED, "nowhere"),
                new TestModule("self", Scope.TEAM, Source.BUNDLED, "self"),
                new TestModule("team-one", Scope.TEAM, Source.BUNDLED, "org-one"),
                new TestModule("org-one", Scope.ORG, Source.BUNDLED),
                new TestModule("ring-a", Scope.TEAM, Source.BUNDLED, "ring-b"),
                new TestModule("ring-b", Scope.TEAM, Source.BUNDLED, "ring-a"))))
                .anySatisfy(v -> assertThat(v).isEqualTo("필요한 모듈 없음(V2): missing → nowhere"))
                .anySatisfy(v -> assertThat(v).isEqualTo("필요한 모듈 없음(V2): self → self"))
                .anySatisfy(v -> assertThat(v).isEqualTo("범위가 다른 모듈은 필요로 할 수 없음: team-one → org-one"))
                .anySatisfy(v -> assertThat(v).isEqualTo("필요한 모듈 순환(V2): ring-a"));
    }

    @Test
    @DisplayName("V5·ARCH-PKG-04: 출처와 마이그레이션 위치가 다르거나 확장 모듈이 업스트림 패키지 안이면 실패")
    void rejectsSourceMismatch() {
        TestModule bundled = new TestModule("bundled", Scope.TEAM, Source.BUNDLED);
        bundled.location = MigrationLocation.COMPANY;
        TestModule extension = new TestModule("extension", Scope.TEAM, Source.EXTENSION);
        extension.location = MigrationLocation.CORE;

        assertThat(ModuleRegistry.violations(List.of(bundled, extension)))
                .anySatisfy(v -> assertThat(v).startsWith("출처와 마이그레이션 위치 다름(V5): bundled"))
                .anySatisfy(v -> assertThat(v).startsWith("출처와 마이그레이션 위치 다름(V5): extension"))
                .anySatisfy(v -> assertThat(v).startsWith("확장 모듈이 업스트림 패키지 안에 있음(ARCH-PKG-04): extension"));
        assertThat(ModuleRegistry.violations(List.of(new FakeExtensionModule()))).isEmpty();
    }

    @Test
    @DisplayName("V6·패키지: EXP 출처·알림 종류·실시간 이벤트 이름 중복, 한 패키지에 모듈 둘이면 실패")
    void rejectsDuplicateNames() {
        TestModule first = new TestModule("first", Scope.TEAM, Source.BUNDLED);
        first.names = Set.of("same.name");
        TestModule second = new TestModule("second", Scope.TEAM, Source.BUNDLED);
        second.names = Set.of("same.name");

        assertThat(ModuleRegistry.violations(List.of(first, second)))
                .contains("EXP 출처 중복(V6): same.name", "알림 종류 중복(V6): same.name",
                        "실시간 이벤트 이름 중복(V6): same.name")
                .anySatisfy(v -> assertThat(v).startsWith("모듈 패키지 중복: second"));
    }

    static final class TestModule implements ModuleDefinition {

        private final String key;
        private final Scope scope;
        private final Source source;
        private final Set<String> requires;
        private String name = "이름";
        private MigrationLocation location = MigrationLocation.NONE;
        private Set<String> names = Set.of();

        TestModule(String key, Scope scope, Source source, String... requires) {
            this.key = key;
            this.scope = scope;
            this.source = source;
            this.requires = Set.of(requires);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "설명";
        }

        @Override
        public Scope scope() {
            return scope;
        }

        @Override
        public Source source() {
            return source;
        }

        @Override
        public Set<String> requires() {
            return requires;
        }

        @Override
        public Set<String> expSources() {
            return names;
        }

        @Override
        public Set<String> notificationKinds() {
            return names;
        }

        @Override
        public Set<String> realtimeEvents() {
            return names;
        }

        @Override
        public MigrationLocation migrationLocation() {
            return location;
        }

    }

}
