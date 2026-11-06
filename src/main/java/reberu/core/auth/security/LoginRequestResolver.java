package reberu.core.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;

class LoginRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String AUTHORIZATION_BASE_URI = "/oauth2/authorization";
    static final String RETURN_PATH = "reberu.return_path";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    LoginRequestResolver(ClientRegistrationRepository clients) {
        delegate = new DefaultOAuth2AuthorizationRequestResolver(clients, AUTHORIZATION_BASE_URI);
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.attributes(attributes -> {
                    if (attributes.containsKey(OidcParameterNames.NONCE)) {
                        String nonce = newNonce();
                        attributes.put(OidcParameterNames.NONCE, nonce);
                        builder.additionalParameters(parameters ->
                                parameters.put(OidcParameterNames.NONCE, hash(nonce)));
                    }
                })));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withReturnPath(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withReturnPath(delegate.resolve(request, clientRegistrationId), request);
    }

    private static OAuth2AuthorizationRequest withReturnPath(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request) {
        if (authorizationRequest == null) {
            return null;
        }
        String returnPath = ReturnPaths.accept(request.getParameter("next"));
        if (returnPath == null) {
            return authorizationRequest;
        }
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .attributes(attributes -> attributes.put(RETURN_PATH, returnPath))
                .build();
    }

    private static String newNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
