package reberu.core.account;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface Accounts {

    Optional<AccountSummary> findByGoogleSub(String googleSub);

    Map<UUID, AccountProfile> profiles(Collection<UUID> accountIds);

    Map<String, Set<UUID>> idsByEmail(Collection<String> normalizedEmails);

    Optional<AccountState> stateOf(UUID accountId);

    Optional<AccountState> lockState(UUID accountId);

    void recordLogin(UUID accountId, GoogleProfile profile, Instant at);

    UUID createWithConsent(GoogleProfile profile, String termsVersion, String privacyVersion, Instant at);

}
