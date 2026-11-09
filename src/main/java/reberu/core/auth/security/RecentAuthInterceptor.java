package reberu.core.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import reberu.core.auth.RequiresRecentAuth;
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;

class RecentAuthInterceptor implements HandlerInterceptor {

    private final Duration window;
    private final Clock clock;

    RecentAuthInterceptor(Duration window, Clock clock) {
        this.window = window;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(RequiresRecentAuth.class)
                && !recentlyAuthenticated()) {
            throw new ReberuException(ErrorCode.REAUTH_REQUIRED);
        }
        return true;
    }

    private boolean recentlyAuthenticated() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AccountAuthentication account
                && account.authenticatedAt().isAfter(clock.instant().minus(window));
    }

}
