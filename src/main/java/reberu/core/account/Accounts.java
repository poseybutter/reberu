package reberu.core.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface Accounts {

    Optional<AccountSummary> findByGoogleSub(String googleSub);

    Optional<AccountState> stateOf(UUID accountId);

    void recordLogin(UUID accountId, GoogleProfile profile, Instant at);

    UUID createWithConsent(GoogleProfile profile, String termsVersion, String privacyVersion, Instant at);

}
