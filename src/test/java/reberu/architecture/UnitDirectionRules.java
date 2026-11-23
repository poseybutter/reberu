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

    static ArchRule onlyCoreEventUsesEventInfrastructure(String root) {
        return noClasses()
                .that().resideInAPackage(root + "..")
                .and().resideOutsideOfPackage(root + ".core.event..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.modulith.events..")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.transaction.event.TransactionalEventListener")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.context.event.EventListener")
                .as("구독은 core 구독 표시만 쓰고 이벤트 저장소를 직접 부르지 않음(ARCH-EVT-10)")
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
