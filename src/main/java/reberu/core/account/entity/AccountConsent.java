package reberu.core.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import reberu.core.support.Owned;
import reberu.core.support.Owned.Ownership;

@Entity
@Owned(Ownership.ACCOUNT)
@Immutable
@Table(name = "account_consent")
public class AccountConsent {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "terms_version", nullable = false, updatable = false)
    private String termsVersion;

    @Column(name = "privacy_version", nullable = false, updatable = false)
    private String privacyVersion;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private Instant agreedAt;

    protected AccountConsent() {
    }

    public static AccountConsent of(UUID id, UUID accountId, String termsVersion, String privacyVersion,
            Instant agreedAt) {
        AccountConsent consent = new AccountConsent();
        consent.id = id;
        consent.accountId = accountId;
        consent.termsVersion = termsVersion;
        consent.privacyVersion = privacyVersion;
        consent.agreedAt = agreedAt;
        return consent;
    }

}
