package reberu.core.support.security;

import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import reberu.core.support.ErrorCode;
import reberu.core.support.ErrorResponseWriter;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data: https://lh3.googleusercontent.com; font-src 'self'; worker-src 'self'; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; "
            + "object-src 'none'; require-trusted-types-for 'script'; trusted-types reberu-html";
    private static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()";
    private static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains";

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ErrorResponseWriter errorWriter) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));

        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .headers(AbstractHttpConfigurer::disable)
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .cors(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint(errorWriter))
                        .accessDeniedHandler(accessDeniedHandler(errorWriter)))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new CrossOriginRejectFilter(errorWriter), CsrfFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
        return http.build();
    }

    @Bean
    FilterRegistrationBean<HeaderWriterFilter> securityHeadersFilter() {
        HeaderWriterFilter filter = new HeaderWriterFilter(List.of(
                new XContentTypeOptionsHeaderWriter(),
                new XXssProtectionHeaderWriter(),
                new CacheControlHeadersWriter(),
                new StaticHeadersWriter("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY),
                new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY),
                new ContentSecurityPolicyHeaderWriter(CONTENT_SECURITY_POLICY),
                new ReferrerPolicyHeaderWriter(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN),
                new PermissionsPolicyHeaderWriter(PERMISSIONS_POLICY)));
        filter.setShouldWriteHeadersEagerly(true);
        FilterRegistrationBean<HeaderWriterFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    private static AuthenticationEntryPoint authenticationEntryPoint(ErrorResponseWriter errorWriter) {
        return (request, response, exception) -> errorWriter.write(request, response, ErrorCode.UNAUTHENTICATED);
    }

    private static AccessDeniedHandler accessDeniedHandler(ErrorResponseWriter errorWriter) {
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
