package reberu.core.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.account.AccountState;
import reberu.core.account.AccountSummary;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.repository.PendingSignupRepository;

@Service
public class LoginService {

    static final Duration PENDING_SIGNUP_LIFETIME = Duration.ofMinutes(15);

    private final Accounts accounts;
    private final PendingSignupRepository pendingSignups;
    private final Clock clock = Clock.systemUTC();

    LoginService(Accounts accounts, PendingSignupRepository pendingSignups) {
        this.accounts = accounts;
        this.pendingSignups = pendingSignups;
    }

    @Transactional
    public LoginOutcome login(GoogleProfile profile, Instant authenticatedAt, UUID requiredAccountId) {
        Optional<AccountSummary> account = accounts.findByGoogleSub(profile.sub());
        if (requiredAccountId != null
                && (account.isEmpty() || !account.get().id().equals(requiredAccountId))) {
            return LoginOutcome.mismatch();
        }
        if (account.isEmpty()) {
            return LoginOutcome.pending();
        }
        if (account.get().state() == AccountState.SUSPENDED) {
            return LoginOutcome.suspended();
        }
        if (account.get().state() != AccountState.ACTIVE) {
            return LoginOutcome.rejected();
        }
        accounts.recordLogin(account.get().id(), profile, clock.instant());
        return LoginOutcome.signedIn(account.get().id(), authenticatedAt);
    }

    @Transactional
    public void startSignup(String sessionIdHash, GoogleProfile profile) {
        Instant now = clock.instant();
        pendingSignups.create(sessionIdHash, profile, now, now.plus(PENDING_SIGNUP_LIFETIME));
    }

    public record LoginOutcome(Kind kind, UUID accountId, Instant authenticatedAt) {

        public enum Kind {
            SIGNED_IN,
            PENDING_SIGNUP,
            SUSPENDED,
            ACCOUNT_MISMATCH,
            REJECTED
        }

        static LoginOutcome signedIn(UUID accountId, Instant at) {
            return new LoginOutcome(Kind.SIGNED_IN, accountId, at);
        }

        static LoginOutcome pending() {
            return new LoginOutcome(Kind.PENDING_SIGNUP, null, null);
        }

        static LoginOutcome suspended() {
            return new LoginOutcome(Kind.SUSPENDED, null, null);
        }

        static LoginOutcome mismatch() {
            return new LoginOutcome(Kind.ACCOUNT_MISMATCH, null, null);
        }

        static LoginOutcome rejected() {
            return new LoginOutcome(Kind.REJECTED, null, null);
        }

    }

}
