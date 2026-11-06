package reberu.core.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CsrfException;
import reberu.core.account.Accounts;
import reberu.core.auth.repository.PendingSignupRepository;
import reberu.core.auth.service.LoginService;
import reberu.core.auth.session.JdbcAuthSessionRepository;
import reberu.core.support.ErrorCode;
import reberu.core.support.ErrorResponseWriter;
import reberu.core.support.WebSecurityDefaults;

@Configuration(proxyBeanMethods = false)
class AuthSecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ErrorResponseWriter errorWriter,
            ClientRegistrationRepository clients, JdbcAuthSessionRepository sessions, Accounts accounts,
            PendingSignupRepository pendingSignups, LoginService loginService) throws Exception {
        Clock clock = Clock.systemUTC();
        LoginHandlers loginHandlers = new LoginHandlers(loginService);
        OidcUserService oidcUserService = new OidcUserService();
        oidcUserService.setRetrieveUserInfo(request -> false);

        WebSecurityDefaults.apply(http, errorWriter, accessDeniedHandler(errorWriter))
                .securityContext(context -> context
                        .securityContextRepository(new SessionSecurityContextRepository(accounts, pendingSignups, clock)))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.none()))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, LoginRequestResolver.AUTHORIZATION_BASE_URI + "/google",
                                "/login/oauth2/code/google", "/api/auth/session").permitAll()
                        .requestMatchers("/api/auth/signup", "/api/auth/signup/consent").access(pendingSignupOnly())
                        .anyRequest().access(accountOnly()))
                .oauth2Login(login -> login
                        .loginPage("/login")
                        .authorizationEndpoint(endpoint -> endpoint
                                .baseUri(LoginRequestResolver.AUTHORIZATION_BASE_URI)
                                .authorizationRequestResolver(new LoginRequestResolver(clients))
                                .authorizationRequestRepository(
                                        new SessionAuthorizationRequestRepository(sessions, clients, clock)))
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                        .authorizedClientRepository(new DiscardingAuthorizedClientRepository())
                        .successHandler(loginHandlers)
                        .failureHandler(loginHandlers));
        return http.build();
    }

    private static AccessDeniedHandler accessDeniedHandler(ErrorResponseWriter errorWriter) {
        AccessDeniedHandler defaults = WebSecurityDefaults.accessDeniedHandler(errorWriter);
        return (request, response, exception) -> {
            if (!(exception instanceof CsrfException)
                    && SecurityContextHolder.getContext().getAuthentication() instanceof PendingSignupAuthentication) {
                errorWriter.write(request, response, ErrorCode.CONSENT_REQUIRED);
                return;
            }
            defaults.handle(request, response, exception);
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> pendingSignupOnly() {
        return (authentication, context) ->
                new AuthorizationDecision(authentication.get() instanceof PendingSignupAuthentication);
    }

    private static AuthorizationManager<RequestAuthorizationContext> accountOnly() {
        return (authentication, context) -> {
            Authentication current = authentication.get();
            return new AuthorizationDecision(current != null && current.isAuthenticated()
                    && !(current instanceof AnonymousAuthenticationToken)
                    && !(current instanceof PendingSignupAuthentication));
        };
    }

    private static final class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }

    }

}
