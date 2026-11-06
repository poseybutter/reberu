package reberu.core.support.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

class AppVersionHeaderFilter extends OncePerRequestFilter {

    static final String VERSION = "X-App-Version";
    static final String MIN_VERSION = "X-App-Min-Version";

    private final String version;
    private final String minVersion;

    AppVersionHeaderFilter(String version, String minVersion) {
        this.version = version;
        this.minVersion = minVersion;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(VERSION, version);
        response.setHeader(MIN_VERSION, minVersion);
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api") || path.startsWith("/api/"));
    }

}
