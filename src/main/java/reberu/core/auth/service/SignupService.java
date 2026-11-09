package reberu.core.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reberu.core.account.AccountState;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.repository.PendingSignupRepository;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.OrgBootstrap;

@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private final Accounts accounts;
    private final PendingSignupRepository pendingSignups;
    private final OrgBootstrap orgBootstrap;
    private final String termsVersion;
    private final String privacyVersion;
    private final Clock clock = Clock.systemUTC();

    SignupService(Accounts accounts, PendingSignupRepository pendingSignups, OrgBootstrap orgBootstrap,
            @Value("${reberu.legal.terms-version}") String termsVersion,
            @Value("${reberu.legal.privacy-version}") String privacyVersion) {
        this.accounts = accounts;
        this.pendingSignups = pendingSignups;
        this.orgBootstrap = orgBootstrap;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    public String termsVersion() {
        return termsVersion;
    }

    public String privacyVersion() {
        return privacyVersion;
    }

    public SignedIn consent(String sessionIdHash, GoogleProfile profile, String agreedTerms, String agreedPrivacy) {
        List<FieldViolation> violations = new ArrayList<>();
        if (!termsVersion.equals(agreedTerms)) {
            violations.add(new FieldViolation("termsVersion", FieldReason.NOT_ALLOWED_VALUE));
        }
        if (!privacyVersion.equals(agreedPrivacy)) {
            violations.add(new FieldViolation("privacyVersion", FieldReason.NOT_ALLOWED_VALUE));
        }
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        if (!pendingSignups.delete(sessionIdHash)) {
            throw new ReberuException(ErrorCode.UNAUTHENTICATED);
        }
        Instant now = clock.instant();
        UUID accountId = createOrFind(profile, now);
        orgBootstrap.grantFirstAdmin(accountId, profile.email());
        return new SignedIn(accountId, now);
    }

    public void decline(String sessionIdHash) {
        pendingSignups.delete(sessionIdHash);
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
    void deleteExpired() {
        int deleted = pendingSignups.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("만료 가입 대기 정리: {}건", deleted);
        }
    }

    private UUID createOrFind(GoogleProfile profile, Instant now) {
        try {
            return accounts.createWithConsent(profile, termsVersion, privacyVersion, now);
        } catch (DataIntegrityViolationException duplicated) {
            return accounts.findByGoogleSub(profile.sub())
                    .filter(account -> account.state() == AccountState.ACTIVE)
                    .map(account -> account.id())
                    .orElseThrow(() -> new ReberuException(ErrorCode.UNAUTHENTICATED));
        }
    }

    public record SignedIn(UUID accountId, Instant authenticatedAt) {
    }

}
