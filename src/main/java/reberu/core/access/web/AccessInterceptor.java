package reberu.core.access.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import reberu.core.access.Access;
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;

class AccessInterceptor implements HandlerInterceptor {

    static final String ORG_CONTEXT = AccessInterceptor.class.getName() + ".org";
    static final String TEAM_CONTEXT = AccessInterceptor.class.getName() + ".team";

    private final AccessDecider decider;

    AccessInterceptor(AccessDecider decider) {
        this.decider = decider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !AccessDeclarations.isApi(request.getRequestURI())) {
            return true;
        }
        Access access = method.getMethodAnnotation(Access.class);
        if (access == null) {
            throw AccessDecider.notFound();
        }
        switch (access.value().scope()) {
            case ORG -> request.setAttribute(ORG_CONTEXT,
                    decider.org(access, accountId(), pathVariable(request, AccessDeclarations.ORG_ID)));
            case TEAM -> request.setAttribute(TEAM_CONTEXT,
                    decider.team(access, accountId(), pathVariable(request, AccessDeclarations.TEAM_ID)));
            case NONE -> {
            }
        }
        return true;
    }

    private static UUID accountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UUID accountId)) {
            throw new ReberuException(ErrorCode.UNAUTHENTICATED);
        }
        return accountId;
    }

    @SuppressWarnings("unchecked")
    private static String pathVariable(HttpServletRequest request, String name) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? ((Map<String, String>) map).get(name) : null;
    }

}
