package reberu.core.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import reberu.core.support.ErrorResponseWriter;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimitExceededException;
import reberu.core.support.RateLimits;

class LoginRateLimitFilter extends OncePerRequestFilter {

    private final RateLimits rateLimits;
    private final ErrorResponseWriter errorWriter;

    LoginRateLimitFilter(RateLimits rateLimits, ErrorResponseWriter errorWriter) {
        this.rateLimits = rateLimits;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            rateLimits.acquire(RateLimit.LOGIN, RateLimits.ipKey(request.getRemoteAddr()));
        }
        catch (RateLimitExceededException limited) {
            response.setHeader(HttpHeaders.RETRY_AFTER, limited.retryAfterSeconds());
            errorWriter.write(request, response, limited.code());
            return;
        }
        chain.doFilter(request, response);
    }

}
