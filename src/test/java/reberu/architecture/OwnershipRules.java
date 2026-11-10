package reberu.architecture;

import jakarta.persistence.Entity;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.core.ResolvableType;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.QueryByExampleExecutor;
import reberu.core.support.Owned;
import reberu.core.support.Owned.Ownership;

final class OwnershipRules {

    private static final List<Class<?>> BROAD_REPOSITORIES = List.of(CrudRepository.class, ListCrudRepository.class,
            PagingAndSortingRepository.class, ListPagingAndSortingRepository.class, QueryByExampleExecutor.class);
    private static final List<String> QUERY_PREFIXES = List.of("find", "get", "read", "query", "search", "stream",
            "count", "exists");
    private static final Set<String> SCOPE_PARAMETERS = Set.of("teamId", "orgId");

    private OwnershipRules() {
    }

    static List<String> entityViolations(Collection<Class<?>> classes) {
        return classes.stream()
                .filter(type -> type.isAnnotationPresent(Entity.class))
                .filter(type -> !type.isAnnotationPresent(Owned.class))
                .map(type -> "소속 선언 없는 엔티티: " + type.getName())
                .toList();
    }

    static List<String> repositoryViolations(Collection<Class<?>> classes) {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : classes) {
            if (!type.isInterface() || !Repository.class.isAssignableFrom(type)) {
                continue;
            }
            Class<?> domain = ResolvableType.forClass(type).as(Repository.class).resolveGeneric(0);
            Owned owned = domain == null ? null : domain.getAnnotation(Owned.class);
            if (owned == null || owned.value() != Ownership.TEAM) {
                continue;
            }
            for (Class<?> broad : BROAD_REPOSITORIES) {
                if (broad.isAssignableFrom(type)) {
                    violations.add("팀 소속 저장소가 식별자만으로 찾는 기본 메서드를 물려받음: " + type.getName()
                            + " extends " + broad.getSimpleName());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                boolean query = QUERY_PREFIXES.stream().anyMatch(method.getName()::startsWith);
                boolean scoped = Arrays.stream(method.getParameters()).map(Parameter::getName)
                        .anyMatch(SCOPE_PARAMETERS::contains);
                if (query && !scoped) {
                    violations.add("팀·조직 식별자 없는 조회 메서드: " + type.getName() + "." + method.getName());
                }
            }
        }
        return violations;
    }

}
