package reberu.core.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.service.LoginService;
import reberu.core.auth.service.LoginService.LoginOutcome;
import reberu.core.auth.session.AuthSession;
import reberu.core.auth.session.SessionAuthentication;
import reberu.core.auth.session.SessionIds;

class LoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    static final String FAILED = "/login?error=failed";
    static final String SUSPENDED = "/login?error=suspended";
    static final String SIGNUP = "/signup";

    private static final Logger log = LoggerFactory.getLogger(LoginHandlers.class);

    private final LoginService loginService;

    LoginHandlers(LoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        SecurityContextHolder.clearContext();
        Optional<GoogleProfile> profile = authentication.getPrincipal() instanceof OidcUser user
                ? GoogleClaims.profileOf(user)
                : Optional.empty();
        if (profile.isEmpty()) {
            fail(request, response, "ID 토큰 값 거부(email_verified·sub·email·hd)");
            return;
        }
        LoginOutcome outcome = loginService.login(profile.get());
        switch (outcome.kind()) {
            case SIGNED_IN -> {
                newSession(request).setAttribute(AuthSession.AUTHENTICATION,
                        new SessionAuthentication(outcome.accountId(), outcome.authenticatedAt()));
                redirect(response, returnPath(request));
            }
            case PENDING_SIGNUP -> {
                HttpSession session = newSession(request);
                loginService.startSignup(SessionIds.sha256(session.getId()), profile.get());
                redirect(response, SIGNUP);
            }
            case SUSPENDED -> {
                log.info("로그인 거부: 정지된 계정");
                redirect(response, SUSPENDED);
            }
            case REJECTED -> fail(request, response, "로그인할 수 없는 계정 상태");
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String reason = exception instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError().getErrorCode()
                : exception.getClass().getSimpleName();
        fail(request, response, reason);
    }

    private static void fail(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        log.info("로그인 실패: {}", reason);
        redirect(response, FAILED);
    }

    private static void redirect(HttpServletResponse response, String path) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", path);
    }

    private static HttpSession newSession(HttpServletRequest request) {
        discardSession(request);
        return request.getSession(true);
    }

    private static void discardSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private static String returnPath(HttpServletRequest request) {
        return request.getAttribute(LoginRequestResolver.RETURN_PATH) instanceof String path
                ? path
                : ReturnPaths.DEFAULT;
    }

}
