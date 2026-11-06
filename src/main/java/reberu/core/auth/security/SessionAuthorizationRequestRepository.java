package reberu.core.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import reberu.core.auth.session.AuthSession;
import reberu.core.auth.session.JdbcAuthSessionRepository;
import reberu.core.auth.session.LoginProgress;
import reberu.core.auth.session.SessionIds;

class SessionAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String REGISTRATION_ID = "google";

    private final JdbcAuthSessionRepository sessions;
    private final ClientRegistrationRepository clients;
    private final Clock clock;

    SessionAuthorizationRequestRepository(JdbcAuthSessionRepository sessions, ClientRegistrationRepository clients,
            Clock clock) {
        this.sessions = sessions;
        this.clients = clients;
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
            HttpServletResponse response) {
        if (authorizationRequest == null) {
            return;
        }
        LoginProgress login = new LoginProgress(
                SessionIds.sha256(authorizationRequest.getState()),
                authorizationRequest.getAttribute(OidcParameterNames.NONCE),
                authorizationRequest.getAttribute(PkceParameterNames.CODE_VERIFIER),
                authorizationRequest.getAttribute(LoginRequestResolver.RETURN_PATH),
                clock.instant());
        request.getSession(true).setAttribute(AuthSession.LOGIN, login);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        HttpSession session = request.getSession(false);
        if (state == null || state.length() > 256 || session == null) {
            return null;
        }
        return sessions.consumeLogin(session.getId(), SessionIds.sha256(state))
                .map(login -> rebuild(state, login, request))
                .orElse(null);
    }

    private OAuth2AuthorizationRequest rebuild(String state, LoginProgress login, HttpServletRequest request) {
        ClientRegistration registration = clients.findByRegistrationId(REGISTRATION_ID);
        if (login.returnPath() != null) {
            request.setAttribute(LoginRequestResolver.RETURN_PATH, login.returnPath());
        }
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state(state)
                .additionalParameters(Map.of(OidcParameterNames.NONCE, LoginRequestResolver.hash(login.nonce())))
                .attributes(attributes -> {
                    attributes.put(OAuth2ParameterNames.REGISTRATION_ID, registration.getRegistrationId());
                    attributes.put(OidcParameterNames.NONCE, login.nonce());
                    attributes.put(PkceParameterNames.CODE_VERIFIER, login.codeVerifier());
                })
                .build();
    }

}
