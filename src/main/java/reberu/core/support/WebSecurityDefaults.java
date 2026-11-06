package reberu.core.support;

import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import reberu.core.support.security.CrossOriginRejectFilter;
import reberu.core.support.security.CsrfCookieFilter;

public final class WebSecurityDefaults {

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    private WebSecurityDefaults() {
    }

    public static HttpSecurity apply(HttpSecurity http, ErrorResponseWriter errorWriter,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));

        return http
                .headers(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(request, response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler(accessDeniedHandler))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new CrossOriginRejectFilter(errorWriter), CsrfFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
    }

    public static AccessDeniedHandler accessDeniedHandler(ErrorResponseWriter errorWriter) {
        return (request, response, exception) -> {
            ErrorCode code = ErrorCode.FORBIDDEN_ACTION;
            if (exception instanceof CsrfException) {
                code = TRUST_RESOLVER.isAuthenticated(SecurityContextHolder.getContext().getAuthentication())
                        ? ErrorCode.CSRF_INVALID
                        : ErrorCode.UNAUTHENTICATED;
            }
            errorWriter.write(request, response, code);
        };
    }

}
