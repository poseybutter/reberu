package reberu.core.support.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimited;
import reberu.core.support.RateLimits;

class RateLimitInterceptor implements HandlerInterceptor {

    static final String API_PREFIX = "/api/";

    private final RateLimits rateLimits;

    RateLimitInterceptor(RateLimits rateLimits) {
        this.rateLimits = rateLimits;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(handler instanceof HandlerMethod method) || pattern == null
                || !pattern.toString().startsWith(API_PREFIX)) {
            return true;
        }
        RateLimited declared = method.getMethodAnnotation(RateLimited.class);
        RateLimit rule = declared == null ? RateLimit.DEFAULT : declared.value();
        rateLimits.acquire(rule, key(rule, request));
        return true;
    }

    private static String key(RateLimit rule, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (rule.key() == RateLimit.Key.ACCOUNT && authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UUID accountId) {
            return RateLimits.accountKey(accountId);
        }
        return RateLimits.ipKey(request.getRemoteAddr());
    }

}
