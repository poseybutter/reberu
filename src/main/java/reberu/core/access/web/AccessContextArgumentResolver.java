package reberu.core.access.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import reberu.core.access.OrgContext;
import reberu.core.access.TeamContext;

class AccessContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type == OrgContext.class || type == TeamContext.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String attribute = parameter.getParameterType() == OrgContext.class
                ? AccessInterceptor.ORG_CONTEXT
                : AccessInterceptor.TEAM_CONTEXT;
        Object context = request == null ? null : request.getAttribute(attribute);
        if (context == null) {
            throw new IllegalStateException("확정되지 않은 컨텍스트 요청: " + parameter.getParameterType().getSimpleName());
        }
        return context;
    }

}
