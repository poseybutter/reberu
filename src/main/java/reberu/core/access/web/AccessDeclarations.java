package reberu.core.access.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.AccessLevel.Scope;
import reberu.core.access.ModuleSwitch;

final class AccessDeclarations {

    static final String API_PREFIX = "/api/";
    static final String ORG_ID = "orgId";
    static final String TEAM_ID = "teamId";
    static final String MODULE_SEGMENT = "mod";
    private static final int MODULE_SEGMENT_INDEX = 4;

    private AccessDeclarations() {
    }

    static boolean isApi(String path) {
        return path != null && path.startsWith(API_PREFIX);
    }

    static List<String> violations(Map<RequestMappingInfo, HandlerMethod> handlers, ModuleSwitch modules) {
        List<String> violations = new ArrayList<>();
        handlers.forEach((info, method) -> {
            for (String pattern : info.getPatternValues()) {
                if (!isApi(pattern)) {
                    continue;
                }
                String where = pattern + " " + method.getShortLogMessage();
                Access access = method.getMethodAnnotation(Access.class);
                if (access == null) {
                    violations.add("권한 선언 없음: " + where);
                    continue;
                }
                Scope scope = access.value().scope();
                if (access.guestAllowed() && access.value() != AccessLevel.ORG_MEMBER) {
                    violations.add("손님 허용은 조직 멤버 권한에만: " + where);
                }
                if (access.guestAllowed() && access.write()) {
                    violations.add("손님 허용은 읽기 API에만: " + where);
                }
                if (scope == Scope.ORG && !has(pattern, ORG_ID)) {
                    violations.add("조직 권한인데 {orgId} 경로 없음: " + where);
                }
                if (scope == Scope.TEAM && !has(pattern, TEAM_ID)) {
                    violations.add("팀 권한인데 {teamId} 경로 없음: " + where);
                }
                if (scope == Scope.NONE && (has(pattern, ORG_ID) || has(pattern, TEAM_ID))) {
                    violations.add("조직·팀 경로인데 조직·팀 권한 아님: " + where);
                }
                if (scope == Scope.TEAM && has(pattern, ORG_ID)) {
                    violations.add("팀 권한 경로에 {orgId} 함께 있음(API-URL-02): " + where);
                }
                moduleViolations(pattern, where, access, method, modules, violations);
            }
        });
        return violations;
    }

    private static void moduleViolations(String pattern, String where, Access access, HandlerMethod method,
            ModuleSwitch modules, List<String> violations) {
        String declared = access.module();
        List<String> segments = List.of(pattern.split("/"));
        int index = segments.indexOf(MODULE_SEGMENT);
        if (index >= 0) {
            String pathKey = index + 1 < segments.size() ? segments.get(index + 1) : "";
            if (index != MODULE_SEGMENT_INDEX) {
                violations.add("모듈 경로는 /api/orgs/{orgId}/mod/{key} 또는 /api/teams/{teamId}/mod/{key}(API-MOD-01): "
                        + where);
            }
            if (!pathKey.equals(declared)) {
                violations.add("경로의 모듈 key와 선언한 모듈 key 다름(API-MOD-02): " + where);
            }
        }
        else if (!declared.isEmpty()) {
            violations.add("모듈 선언인데 /mod/{key} 경로 아님(API-MOD-01): " + where);
        }
        if (!declared.isEmpty()) {
            Optional<Scope> installed = modules.installedScope(declared);
            if (installed.isEmpty()) {
                violations.add("설치되지 않은 모듈 key 선언: " + where);
            }
            else if (installed.get() != access.value().scope()) {
                violations.add("모듈 범위와 권한 범위 다름: " + where);
            }
        }
        modules.owner(ClassUtils.getUserClass(method.getBeanType()))
                .filter(owner -> !owner.equals(declared))
                .ifPresent(owner -> violations.add("모듈 API에 그 모듈 key 선언 없음(V3): " + where));
    }

    private static boolean has(String pattern, String variable) {
        return List.of(pattern.split("/")).contains("{" + variable + "}");
    }

}
