package reberu.core.account.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.account.AccountProfile;
import reberu.core.account.AccountState;
import reberu.core.account.AccountSummary;
import reberu.core.account.AccountWithdrawn;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.account.LockedAccount;
import reberu.core.account.entity.Account;
import reberu.core.account.entity.AccountConsent;
import reberu.core.account.repository.AccountConsentRepository;
import reberu.core.account.repository.AccountRepository;
import reberu.core.support.EmailAddresses;
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;
import reberu.core.support.Uuids;

@Service
class AccountService implements Accounts {

    private static final String DELETED_SUB_PREFIX = "deleted:";

    private static final int DELETED_SUB_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final AccountConsentRepository consents;
    private final ApplicationEventPublisher events;

    AccountService(AccountRepository accounts, AccountConsentRepository consents, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.consents = consents;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountSummary> findByGoogleSub(String googleSub) {
        return accounts.findSummaryByGoogleSub(googleSub);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, AccountProfile> profiles(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AccountProfile> profiles = new HashMap<>();
        for (Account account : accounts.findAllById(Set.copyOf(accountIds))) {
            profiles.put(account.id(), new AccountProfile(account.id(), account.state(), account.displayName(),
                    account.email(), account.lastLoginAt()));
        }
        return profiles;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Set<UUID>> idsByEmail(Collection<String> normalizedEmails) {
        if (normalizedEmails.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<UUID>> ids = new HashMap<>();
        for (Account account : accounts.findByEmailIn(Set.copyOf(normalizedEmails))) {
            ids.computeIfAbsent(account.email(), ignored -> new HashSet<>()).add(account.id());
        }
        return ids;
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
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedAccount> lockWithEmailSharers(UUID accountId) {
        String email = accounts.findEmailById(accountId).map(EmailAddresses::normalize).orElse(null);
        List<Account> sharers = email == null ? List.of() : accounts.findForUpdateByEmail(email);
        Optional<Account> locked = accounts.findForUpdate(accountId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        Account account = locked.get();
        String lockedEmail = account.email() == null ? null : EmailAddresses.normalize(account.email());
        if (!Objects.equals(email, lockedEmail)) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        boolean shared = sharers.stream().anyMatch(sharer -> !sharer.id().equals(accountId));
        return Optional.of(new LockedAccount(account.state(), lockedEmail, shared));
    }

    @Override
    @Transactional
    public AccountState recordLogin(UUID accountId, GoogleProfile profile, Instant at) {
        Account account = accounts.findForUpdate(accountId)
                .orElseThrow(() -> new IllegalStateException("로그인 정보를 갱신할 계정 없음"));
        if (account.state() == AccountState.ACTIVE) {
            account.recordLogin(profile, at);
        }
        return account.state();
    }

    @Override
    @Transactional
    public UUID createWithConsent(GoogleProfile profile, String termsVersion, String privacyVersion, Instant at) {
        Account account = accounts.saveAndFlush(Account.create(Uuids.v7(), profile, at));
        consents.save(AccountConsent.of(Uuids.v7(), account.id(), termsVersion, privacyVersion, at));
        return account.id();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void withdraw(UUID accountId, Instant at) {
        accounts.findForUpdate(accountId)
                .orElseThrow(() -> new IllegalStateException("탈퇴할 계정 없음"))
                .withdraw(DELETED_SUB_PREFIX + randomHex(), at);
        events.publishEvent(new AccountWithdrawn(accountId));
    }

    private static String randomHex() {
        byte[] bytes = new byte[DELETED_SUB_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

}
