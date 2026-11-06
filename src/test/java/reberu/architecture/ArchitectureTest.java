package reberu.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import reberu.ReberuApplication;

class ArchitectureTest {

    private static final String ROOT = "reberu";
    private static final String FIXTURE = "reberu.architecture.fixture";

    private static final List<String> UNITS = List.of(
            "core.access", "core.account", "core.audit", "core.auth", "core.calendar", "core.event",
            "core.module", "core.notification", "core.realtime", "core.support", "core.tenancy",
            "attendance", "progression", "project", "report", "task", "todo",
            "module.briefing", "module.certificate", "module.halloffame", "module.healing",
            "module.orgspace", "module.outbound", "module.praise", "module.quest", "module.space");

    private static final ImportOption ONLY_TESTS = new ImportOption.OnlyIncludeTests();

    @Test
    @DisplayName("단위 목록과 패키지 구조 일치")
    void unitsMatchPackageStructure() {
        List<String> units = ApplicationModules.of(ReberuApplication.class).stream()
                .map(ApplicationModule::getIdentifier)
                .map(Object::toString)
                .toList();

        assertThat(units).containsExactlyInAnyOrderElementsOf(UNITS);
    }

    @Test
    @DisplayName("단위 사이 허용 의존·순환 없음·내부 패키지 비공개")
    void modulithStructureIsValid() {
        ApplicationModules.of(ReberuApplication.class).verify();
    }

    @Test
    @DisplayName("core → 고정 기능 → 모듈 반대 방향 의존 없음")
    void unitsDependInOneDirection() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);

        UnitDirectionRules.coreDependsOnlyOnCore(ROOT).check(classes);
        UnitDirectionRules.fixedFeaturesDoNotDependOnModules(ROOT).check(classes);
    }

    @Test
    @DisplayName("실패 사례: core가 고정 기능에 의존")
    void coreDependingOnFixedFeatureFails() {
        String root = FIXTURE + ".reverse";

        assertThatThrownBy(() -> UnitDirectionRules.coreDependsOnlyOnCore(root).check(fixtureClasses(root)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CoreAlpha");
    }

    @Test
    @DisplayName("실패 사례: 고정 기능이 모듈에 의존")
    void fixedFeatureDependingOnModuleFails() {
        String root = FIXTURE + ".upward";

        assertThatThrownBy(() -> UnitDirectionRules.fixedFeaturesDoNotDependOnModules(root).check(fixtureClasses(root)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TaskThing");
    }

    @Test
    @DisplayName("실패 사례: 선언하지 않은 고정 기능 사이 의존")
    void undeclaredDependencyFails() {
        ApplicationModules modules = ApplicationModules.of(FIXTURE + ".undeclared", ONLY_TESTS);

        assertThatThrownBy(modules::verify)
                .hasMessageContaining("ProjectThing")
                .hasMessageContaining("TaskThing");
    }

    @Test
    @DisplayName("실패 사례: 다른 단위의 내부 패키지 참조")
    void internalPackageAccessFails() {
        ApplicationModules modules = ApplicationModules.of(FIXTURE + ".internal", ONLY_TESTS);

        assertThatThrownBy(modules::verify)
                .hasMessageContaining("Hidden");
    }

    private static JavaClasses fixtureClasses(String root) {
        return new ClassFileImporter()
                .withImportOption(ONLY_TESTS)
                .importPackages(root);
    }

}
