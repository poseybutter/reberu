package reberu.core.auth.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reberu.core.account.AccountWithdrawn;
import reberu.core.auth.AuthSessions;
import reberu.core.event.DomainEventListener;

class AccountWithdrawnSessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(AccountWithdrawnSessionRevoker.class);

    private final AuthSessions sessions;

    AccountWithdrawnSessionRevoker(AuthSessions sessions) {
        this.sessions = sessions;
    }

    @DomainEventListener
    void on(AccountWithdrawn event) {
        int revoked = sessions.revokeAll(event.accountId());
        log.info("탈퇴로 세션 끊음: 계정 {}, 세션 {}개", event.accountId(), revoked);
    }

}
