package reberu.core.access.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.AccessLevel.Scope;

final class AccessDeclarations {

    static final String API_PREFIX = "/api/";
    static final String ORG_ID = "orgId";
    static final String TEAM_ID = "teamId";

    private AccessDeclarations() {
    }

    static boolean isApi(String path) {
        return path != null && path.startsWith(API_PREFIX);
    }

    static List<String> violations(Map<RequestMappingInfo, HandlerMethod> handlers) {
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
            }
        });
        return violations;
    }

    private static boolean has(String pattern, String variable) {
        return List.of(pattern.split("/")).contains("{" + variable + "}");
    }

}
