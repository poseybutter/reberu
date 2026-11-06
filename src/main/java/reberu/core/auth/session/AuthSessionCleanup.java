package reberu.core.auth.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class AuthSessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionCleanup.class);

    private final JdbcAuthSessionRepository repository;

    AuthSessionCleanup(JdbcAuthSessionRepository repository) {
        this.repository = repository;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
    void deleteExpired() {
        int deleted = repository.deleteExpired();
        if (deleted > 0) {
            log.info("만료 세션 정리: {}건", deleted);
        }
    }

}
