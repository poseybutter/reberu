package reberu.core.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import reberu.core.account.AccountState;
import reberu.core.account.Accounts;
import reberu.core.auth.repository.PendingSignupRepository;
import reberu.core.auth.session.AuthSession;
import reberu.core.auth.session.SessionAuthentication;
import reberu.core.auth.session.SessionIds;

class SessionSecurityContextRepository implements SecurityContextRepository {

    private final Accounts accounts;
    private final PendingSignupRepository pendingSignups;
    private final Clock clock;

    SessionSecurityContextRepository(Accounts accounts, PendingSignupRepository pendingSignups, Clock clock) {
        this.accounts = accounts;
        this.pendingSignups = pendingSignups;
        this.clock = clock;
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        Supplier<SecurityContext> loader = () -> load(request);
        return new DeferredSecurityContext() {

            private SecurityContext context;

            @Override
            public SecurityContext get() {
                if (context == null) {
                    context = loader.get();
                }
                return context;
            }

            @Override
            public boolean isGenerated() {
                return get().getAuthentication() == null;
            }

        };
    }

    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return load(holder.getRequest());
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(AuthSession.AUTHENTICATION) != null;
    }

    private SecurityContext load(HttpServletRequest request) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return context;
        }
        Authentication authentication = authenticationOf(session);
        if (authentication != null) {
            context.setAuthentication(authentication);
        }
        return context;
    }

    private Authentication authenticationOf(HttpSession session) {
        if (session.getAttribute(AuthSession.AUTHENTICATION) instanceof SessionAuthentication signedIn) {
            if (accounts.stateOf(signedIn.accountId()).orElse(null) != AccountState.ACTIVE) {
                session.invalidate();
                return null;
            }
            return new AccountAuthentication(signedIn.accountId(), signedIn.authenticatedAt());
        }
        String hash = SessionIds.sha256(session.getId());
        return pendingSignups.find(hash, clock.instant())
                .map(profile -> new PendingSignupAuthentication(hash, profile))
                .orElse(null);
    }

}
