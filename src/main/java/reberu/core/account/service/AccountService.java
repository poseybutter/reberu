package reberu.core.account.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.account.AccountState;
import reberu.core.account.AccountSummary;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.account.entity.Account;
import reberu.core.account.entity.AccountConsent;
import reberu.core.account.repository.AccountConsentRepository;
import reberu.core.account.repository.AccountRepository;
import reberu.core.support.Uuids;

@Service
class AccountService implements Accounts {

    private final AccountRepository accounts;
    private final AccountConsentRepository consents;

    AccountService(AccountRepository accounts, AccountConsentRepository consents) {
        this.accounts = accounts;
        this.consents = consents;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountSummary> findByGoogleSub(String googleSub) {
        return accounts.findByGoogleSub(googleSub).map(account -> new AccountSummary(account.id(), account.state()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountState> stateOf(UUID accountId) {
        return accounts.findStateById(accountId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AccountState> lockState(UUID accountId) {
        return accounts.findForUpdate(accountId).map(Account::state);
    }

    @Override
    @Transactional
    public void recordLogin(UUID accountId, GoogleProfile profile, Instant at) {
        accounts.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("로그인 정보를 갱신할 계정 없음"))
                .recordLogin(profile, at);
    }

    @Override
    @Transactional
    public UUID createWithConsent(GoogleProfile profile, String termsVersion, String privacyVersion, Instant at) {
        Account account = accounts.saveAndFlush(Account.create(Uuids.v7(), profile, at));
        consents.save(AccountConsent.of(Uuids.v7(), account.id(), termsVersion, privacyVersion, at));
        return account.id();
    }

}
