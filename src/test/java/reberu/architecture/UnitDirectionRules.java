package reberu.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

final class UnitDirectionRules {

    private UnitDirectionRules() {
    }

    static ArchRule coreDependsOnlyOnCore(String root) {
        return noClasses()
                .that().resideInAPackage(root + ".core..")
                .should().dependOnClassesThat(resideInAPackage(root + "..")
                        .and(resideInAPackage(root + ".core..").negate()))
                .as("core는 core 밖의 단위에 의존하지 않음")
                .allowEmptyShould(true);
    }

    static ArchRule fixedFeaturesDoNotDependOnModules(String root) {
        return noClasses()
                .that().resideInAPackage(root + "..")
                .and().resideOutsideOfPackages(root + ".core..", root + ".module..")
                .should().dependOnClassesThat().resideInAPackage(root + ".module..")
                .as("고정 기능은 모듈에 의존하지 않음")
                .allowEmptyShould(true);
    }

}
