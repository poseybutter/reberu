package reberu.core.event.delivery;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import reberu.core.access.AccessLevel.Scope;
import reberu.core.access.ModuleSwitch;
import reberu.core.event.DomainEventListener;
import reberu.core.event.OrgScoped;
import reberu.core.event.TeamScoped;

public final class ListenerDeclarations {

    private ListenerDeclarations() {
    }

    public static List<String> violations(Collection<Class<?>> types, ModuleSwitch modules) {
        List<String> violations = new ArrayList<>();
        for (Class<?> raw : types) {
            Class<?> type = ClassUtils.getUserClass(raw);
            Optional<String> owner = modules.owner(type);
            MethodIntrospector.selectMethods(type, (MethodIntrospector.MetadataLookup<Boolean>) method ->
                    AnnotatedElementUtils.hasAnnotation(method, DomainEventListener.class) ? Boolean.TRUE : null)
                    .keySet().forEach(method -> check(method, owner, modules, violations));
        }
        return violations;
    }

    private static void check(Method method, Optional<String> owner, ModuleSwitch modules, List<String> violations) {
        String where = method.getDeclaringClass().getName() + "." + method.getName();
        if (method.getParameterCount() != 1) {
            violations.add("구독 메서드는 이벤트 하나만 받음: " + where);
            return;
        }
        if (owner.isEmpty()) {
            return;
        }
        Class<?> event = method.getParameterTypes()[0];
        Optional<Scope> scope = modules.installedScope(owner.get());
        if (scope.isPresent() && scope.get() == Scope.TEAM && !TeamScoped.class.isAssignableFrom(event)) {
            violations.add("팀 모듈 구독자는 TeamScoped 이벤트만 받음(ARCH-EVT-05): " + where);
        }
        if (scope.isPresent() && scope.get() == Scope.ORG && !OrgScoped.class.isAssignableFrom(event)) {
            violations.add("조직 모듈 구독자는 OrgScoped 이벤트만 받음(ARCH-EVT-05): " + where);
        }
    }

}
