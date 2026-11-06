package reberu.core.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthSessionRepositoryTest {

    private static final SessionProperties PROPERTIES =
            new SessionProperties(Duration.ofHours(8), Duration.ofDays(7), 10);
    private static final UUID ACCOUNT = UUID.fromString("0190f2a4-0000-7000-8000-000000000001");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("0190f2a4-0000-7000-8000-000000000002");
    private static final String STATE_HASH = SessionIds.sha256("login-state-value");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-06T00:00:00Z"));
    private JdbcAuthSessionRepository repository;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM auth_session").update();
        repository = new JdbcAuthSessionRepository(jdbc, transactions, PROPERTIES, clock);
    }

    @Test
    @DisplayName("DB에 세션 ID 원문 없이 SHA-256 해시만 저장")
    void storesOnlyHash() {
        AuthSession session = signedIn(ACCOUNT);

        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM auth_session").query().listOfRows();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(column(rows.get(0), "session_id_hash")))
                .isEqualTo(SessionIds.sha256(session.getId()));
        assertThat(rows.get(0).values()).noneMatch(value -> value != null && value.toString().contains(session.getId()));
    }

    @Test
    @DisplayName("원문으로 DB를 찾을 수 없고, DB의 해시 값으로는 세션을 찾을 수 없음")
    void storedValuesCannotBeUsedAsSessionId() {
        AuthSession session = signedIn(ACCOUNT);
        String storedHash = jdbc.sql("SELECT session_id_hash FROM auth_session").query(String.class).single();

        Integer byRawId = jdbc.sql("SELECT COUNT(*) FROM auth_session WHERE session_id_hash = :id")
                .param("id", session.getId()).query(Integer.class).single();

        assertThat(byRawId).isZero();
        assertThat(repository.findById(storedHash)).isNull();
        assertThat(repository.findById(session.getId()).<SessionAuthentication>getAttribute(AuthSession.AUTHENTICATION)
                .accountId()).isEqualTo(ACCOUNT);
    }

    @Test
    @DisplayName("정해진 속성 밖의 이름·타입 저장 거부")
    void rejectsUnknownAttributes() {
        AuthSession session = repository.createSession();

        assertThatThrownBy(() -> session.setAttribute("SPRING_SECURITY_CONTEXT", "context"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session.setAttribute(AuthSession.AUTHENTICATION, ACCOUNT.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session.setMaxInactiveInterval(Duration.ofDays(365)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(session.getAttributeNames()).isEmpty();
    }

    @Test
    @DisplayName("세션 ID 재발급: 이전 행 삭제, 이전 ID로 찾을 수 없음")
    void changingIdRemovesOldRow() {
        AuthSession session = signedIn(ACCOUNT);
        String oldId = session.getId();

        String newId = session.changeSessionId();
        repository.save(session);

        assertThat(newId).isNotEqualTo(oldId);
        assertThat(repository.findById(oldId)).isNull();
        assertThat(hashes()).containsExactly(SessionIds.sha256(newId));
        assertThat(repository.findById(newId)).isNotNull();
    }

    @Test
    @DisplayName("유휴 8시간 만료, 쓰는 동안은 연장")
    void idleTimeoutExpiresUnusedSession() {
        AuthSession session = signedIn(ACCOUNT);

        clock.advance(Duration.ofHours(7));
        AuthSession used = repository.findById(session.getId());
        used.setLastAccessedTime(clock.instant());
        repository.save(used);
        clock.advance(Duration.ofHours(7));

        assertThat(repository.findById(session.getId())).isNotNull();

        clock.advance(Duration.ofHours(8));

        assertThat(repository.findById(session.getId())).isNull();
        assertThat(hashes()).isEmpty();
    }

    @Test
    @DisplayName("계속 써도 최대 수명 7일에 만료")
    void maxLifetimeExpiresActiveSession() {
        AuthSession session = signedIn(ACCOUNT);

        for (int hour = 0; hour < 7 * 24; hour += 6) {
            clock.advance(Duration.ofHours(6));
            AuthSession used = repository.findById(session.getId());
            if (used == null) {
                assertThat(clock.instant()).isAfterOrEqualTo(session.getCreationTime().plus(Duration.ofDays(7)));
                return;
            }
            used.setLastAccessedTime(clock.instant());
            repository.save(used);
        }
        assertThat(repository.findById(session.getId())).isNull();
    }

    @Test
    @DisplayName("로그인 전 세션은 15분 뒤 만료")
    void anonymousSessionExpiresAfterFifteenMinutes() {
        AuthSession session = repository.createSession();
        session.setAttribute(AuthSession.LOGIN, login(clock.instant()));
        repository.save(session);

        clock.advance(Duration.ofMinutes(14));
        assertThat(repository.findById(session.getId())).isNotNull();

        clock.advance(Duration.ofMinutes(1));
        assertThat(repository.findById(session.getId())).isNull();
    }

    @Test
    @DisplayName("마지막 사용 시각은 1분에 한 번만 기록")
    void lastUsedIsWrittenAtMostOncePerMinute() {
        AuthSession session = signedIn(ACCOUNT);
        Instant created = clock.instant();

        clock.advance(Duration.ofSeconds(30));
        touch(session.getId());
        assertThat(lastUsed()).isEqualTo(created);

        clock.advance(Duration.ofSeconds(31));
        touch(session.getId());
        assertThat(lastUsed()).isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("계정당 동시 세션 상한 10개, 넘으면 가장 오래된 세션 삭제")
    void concurrentSessionLimitDropsOldest() {
        List<AuthSession> sessions = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            sessions.add(signedIn(ACCOUNT));
            clock.advance(Duration.ofSeconds(1));
        }
        AuthSession other = signedIn(OTHER_ACCOUNT);

        assertThat(repository.findById(sessions.get(0).getId())).isNull();
        for (AuthSession session : sessions.subList(1, 11)) {
            assertThat(repository.findById(session.getId())).isNotNull();
        }
        assertThat(repository.findById(other.getId())).isNotNull();
    }

    @Test
    @DisplayName("로그인 진행 값: state가 맞을 때 한 번만 사용")
    void loginProgressIsUsedOnce() {
        AuthSession session = withLogin();

        assertThat(repository.consumeLogin(session.getId(), SessionIds.sha256("other-state"))).isEmpty();
        Optional<LoginProgress> first = repository.consumeLogin(session.getId(), STATE_HASH);
        Optional<LoginProgress> second = repository.consumeLogin(session.getId(), STATE_HASH);

        assertThat(first).hasValueSatisfying(login -> assertThat(login.nonce()).isEqualTo("nonce-value"));
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("로그인 진행 값: 10분 뒤 무효")
    void loginProgressExpiresAfterTenMinutes() {
        AuthSession session = withLogin();

        clock.advance(Duration.ofMinutes(10));

        assertThat(repository.consumeLogin(session.getId(), STATE_HASH)).isEmpty();
    }

    @Test
    @DisplayName("로그인 진행 값: 동시에 두 번 써도 한 번만 성공")
    void loginProgressIsUsedOnceUnderConcurrency() throws Exception {
        AuthSession session = withLogin();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            return repository.consumeLogin(session.getId(), STATE_HASH).isPresent();
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(attempt);
            Future<Boolean> second = executor.submit(attempt);
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("요청 중 다른 곳에서 지운 값을 요청 끝 저장이 되살리지 않음")
    void saveDoesNotRestoreConsumedLogin() {
        AuthSession session = withLogin();
        AuthSession inRequest = repository.findById(session.getId());

        repository.consumeLogin(session.getId(), STATE_HASH);
        clock.advance(Duration.ofMinutes(2));
        inRequest.setLastAccessedTime(clock.instant());
        repository.save(inRequest);

        assertThat(repository.consumeLogin(session.getId(), STATE_HASH)).isEmpty();
    }

    @Test
    @DisplayName("계정별 즉시 무효화, 끊긴 세션은 저장해도 되살아나지 않음")
    void revokeAllRemovesAccountSessions() {
        AuthSession first = signedIn(ACCOUNT);
        AuthSession second = signedIn(ACCOUNT);
        AuthSession other = signedIn(OTHER_ACCOUNT);
        AuthSession inRequest = repository.findById(first.getId());

        int revoked = repository.revokeAll(ACCOUNT);
        clock.advance(Duration.ofMinutes(2));
        inRequest.setLastAccessedTime(clock.instant());
        repository.save(inRequest);

        assertThat(revoked).isEqualTo(2);
        assertThat(repository.findById(first.getId())).isNull();
        assertThat(repository.findById(second.getId())).isNull();
        assertThat(repository.findById(other.getId())).isNotNull();
        assertThat(hashes()).containsExactly(SessionIds.sha256(other.getId()));
    }

    @Test
    @DisplayName("정리 작업: 만료된 행만 삭제")
    void deleteExpiredRemovesOnlyExpiredRows() {
        AuthSession anonymous = repository.createSession();
        anonymous.setAttribute(AuthSession.LOGIN, login(clock.instant()));
        repository.save(anonymous);
        AuthSession member = signedIn(ACCOUNT);

        clock.advance(Duration.ofMinutes(15));

        assertThat(repository.deleteExpired()).isEqualTo(1);
        assertThat(hashes()).containsExactly(SessionIds.sha256(member.getId()));
    }

    @Test
    @DisplayName("형식이 다른 세션 ID는 DB 조회 없이 거부")
    void malformedIdsAreIgnored() {
        assertThat(repository.findById(null)).isNull();
        assertThat(repository.findById("")).isNull();
        assertThat(repository.findById("a".repeat(44))).isNull();
        assertThat(repository.findById("' OR '1'='1")).isNull();
    }

    private AuthSession signedIn(UUID accountId) {
        AuthSession session = repository.createSession();
        session.setAttribute(AuthSession.AUTHENTICATION, new SessionAuthentication(accountId, clock.instant()));
        repository.save(session);
        return session;
    }

    private AuthSession withLogin() {
        AuthSession session = repository.createSession();
        session.setAttribute(AuthSession.LOGIN, login(clock.instant()));
        repository.save(session);
        return session;
    }

    private static LoginProgress login(Instant startedAt) {
        return new LoginProgress(STATE_HASH, "nonce-value", "v".repeat(43), "/teams", startedAt);
    }

    private void touch(String id) {
        AuthSession session = repository.findById(id);
        session.setLastAccessedTime(clock.instant());
        repository.save(session);
    }

    private Instant lastUsed() {
        return jdbc.sql("SELECT last_used_at FROM auth_session")
                .query((rs, row) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC))
                .single();
    }

    private List<String> hashes() {
        return jdbc.sql("SELECT session_id_hash FROM auth_session").query(String.class).list();
    }

    private static String column(Map<String, Object> row, String name) {
        return row.keySet().stream().filter(key -> key.equalsIgnoreCase(name)).findFirst().orElseThrow();
    }

    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

    }

}
