package reberu.core.account.repository;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import reberu.core.account.entity.AccountConsent;

public interface AccountConsentRepository extends Repository<AccountConsent, UUID> {

    AccountConsent save(AccountConsent consent);

}
