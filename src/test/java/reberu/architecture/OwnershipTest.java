package reberu.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ownershipfixture.BroadTeamThingRepository;
import ownershipfixture.LooseTeamThingRepository;
import ownershipfixture.TeamThing;
import ownershipfixture.UnownedEntity;

class OwnershipTest {

    @Test
    @DisplayName("ARCH-TEST-03·DOM-TEST-01: 모든 엔티티 소속 선언, 팀 소속 저장소는 팀·조직 식별자로만 조회")
    void mainCodeFollowsOwnershipRules() {
        List<Class<?>> classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("reberu")
                .stream()
                .<Class<?>>map(JavaClass::reflect)
                .toList();

        assertThat(classes).anyMatch(type -> type.isAnnotationPresent(Entity.class));
        assertThat(OwnershipRules.entityViolations(classes)).isEmpty();
        assertThat(OwnershipRules.repositoryViolations(classes)).isEmpty();
    }

    @Test
    @DisplayName("ARCH-TEST-03 위반 사례: 소속 없는 엔티티, 기본 CRUD 물려받은 팀 저장소, 팀 식별자 없는 조회")
    void detectsOwnershipViolations() {
        List<Class<?>> fixtures = List.of(UnownedEntity.class, TeamThing.class, BroadTeamThingRepository.class,
                LooseTeamThingRepository.class);

        assertThat(OwnershipRules.entityViolations(fixtures)).containsExactly(
                "소속 선언 없는 엔티티: " + UnownedEntity.class.getName());
        assertThat(OwnershipRules.repositoryViolations(fixtures))
                .contains("팀·조직 식별자 없는 조회 메서드: " + LooseTeamThingRepository.class.getName() + ".findByName")
                .anySatisfy(violation -> assertThat(violation)
                        .startsWith("팀 소속 저장소가 식별자만으로 찾는 기본 메서드를 물려받음: "
                                + BroadTeamThingRepository.class.getName()))
                .noneMatch(violation -> violation.endsWith(".findByTeamIdAndName"));
    }

}
