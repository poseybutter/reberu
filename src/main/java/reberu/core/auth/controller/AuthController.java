package reberu.core.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.auth.dto.ConsentRequest;
import reberu.core.auth.dto.ConsentResponse;
import reberu.core.auth.dto.SessionResponse;
import reberu.core.auth.dto.SignupResponse;
import reberu.core.auth.security.AccountAuthentication;
import reberu.core.auth.security.PendingSignupAuthentication;
import reberu.core.auth.security.ReturnPaths;
import reberu.core.auth.service.SignupService;
import reberu.core.auth.service.SignupService.SignedIn;
import reberu.core.auth.session.AuthSession;
import reberu.core.auth.session.SessionAuthentication;
import reberu.core.tenancy.Deployment;
import reberu.core.tenancy.HomeScreens;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final SignupService signupService;
    private final HomeScreens homeScreens;
    private final Deployment deployment;

    AuthController(SignupService signupService, HomeScreens homeScreens, Deployment deployment) {
        this.signupService = signupService;
        this.homeScreens = homeScreens;
        this.deployment = deployment;
    }

    @GetMapping("/session")
    @Access(AccessLevel.PUBLIC)
    SessionResponse session(Authentication authentication) {
        if (authentication instanceof AccountAuthentication account) {
            return SessionResponse.authenticated(deployment.mode(), account.accountId(), account.authenticatedAt(),
                    homeScreens.next(account.accountId()));
        }
        if (authentication instanceof PendingSignupAuthentication) {
            return SessionResponse.signupPending(deployment.mode());
        }
        return SessionResponse.anonymous(deployment.mode());
    }

    @GetMapping("/signup")
    @Access(AccessLevel.SIGNUP_PENDING)
    SignupResponse signup(PendingSignupAuthentication pending) {
        return SignupResponse.from(pending.profile(), signupService.termsVersion(), signupService.privacyVersion());
    }

    @PostMapping("/signup/consent")
    @Access(AccessLevel.SIGNUP_PENDING)
    ResponseEntity<ConsentResponse> consent(PendingSignupAuthentication pending,
            @Valid @RequestBody ConsentRequest body, HttpServletRequest request) {
        SignedIn signedIn = signupService.consent(pending.sessionIdHash(), pending.profile(),
                body.termsVersion(), body.privacyVersion());
        newSession(request).setAttribute(AuthSession.AUTHENTICATION,
                new SessionAuthentication(signedIn.accountId(), signedIn.authenticatedAt()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ConsentResponse(signedIn.accountId(), signedIn.authenticatedAt(), ReturnPaths.DEFAULT));
    }

    @DeleteMapping("/signup")
    @Access(AccessLevel.SIGNUP_PENDING)
    ResponseEntity<Void> decline(PendingSignupAuthentication pending, HttpServletRequest request) {
        signupService.decline(pending.sessionIdHash());
        invalidate(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    @Access(AccessLevel.PUBLIC)
    ResponseEntity<Void> logout(HttpServletRequest request) {
        invalidate(request);
        return ResponseEntity.noContent().build();
    }

    private static HttpSession newSession(HttpServletRequest request) {
        invalidate(request);
        return request.getSession(true);
    }

    private static void invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

}
