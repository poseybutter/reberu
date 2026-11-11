package reberu.core.auth.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import reberu.core.auth.AuthSessions;
import reberu.core.tenancy.OrgAdminGranted;

class OrgAdminSessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(OrgAdminSessionRevoker.class);

    private final AuthSessions sessions;

    OrgAdminSessionRevoker(AuthSessions sessions) {
        this.sessions = sessions;
    }

    @ApplicationModuleListener
    void on(OrgAdminGranted event) {
        int revoked = sessions.revokeAll(event.accountId());
        log.info("조직 관리자 지정으로 세션 끊음: 조직 {}, 계정 {}, 세션 {}개", event.orgId(), event.accountId(), revoked);
    }

}
