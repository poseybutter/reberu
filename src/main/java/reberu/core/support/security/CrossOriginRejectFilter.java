package reberu.core.support.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import reberu.core.support.ErrorCode;
import reberu.core.support.ErrorResponseWriter;

class CrossOriginRejectFilter extends OncePerRequestFilter {

    private final ErrorResponseWriter errorWriter;

    CrossOriginRejectFilter(ErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (CorsUtils.isCorsRequest(request)) {
            errorWriter.write(request, response, ErrorCode.FORBIDDEN_ACTION);
            return;
        }
        chain.doFilter(request, response);
    }

}
