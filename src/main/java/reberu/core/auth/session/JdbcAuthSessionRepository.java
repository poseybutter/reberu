package reberu.core.auth.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.SessionRepository;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.core.auth.AuthSessions;

public class JdbcAuthSessionRepository implements SessionRepository<AuthSession>, AuthSessions {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final SessionProperties properties;
    private final Clock clock;

    public JdbcAuthSessionRepository(JdbcClient jdbc, TransactionTemplate transactions,
            SessionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public AuthSession createSession() {
        return AuthSession.create(properties, clock.instant());
    }

    @Override
    public void save(AuthSession session) {
        transactions.executeWithoutResult(status -> {
            if (session.isNew()) {
                insert(session);
            } else if (!update(session)) {
                return;
            }
            if (session.authenticationChanged() && session.authentication() != null) {
                enforceLimit(session.authentication().accountId());
            }
            session.markSaved();
        });
    }

    @Override
    public AuthSession findById(String id) {
        if (!SessionIds.isId(id)) {
            return null;
        }
        String hash = SessionIds.sha256(id);
        Instant now = clock.instant();
        Optional<Row> row = select(hash);
        if (row.isEmpty()) {
            return null;
        }
        if (!row.get().expiresAt().isAfter(now)) {
            deleteHash(hash, now);
            return null;
        }
        return AuthSession.load(properties, id, row.get().createdAt(), row.get().lastUsedAt(),
                row.get().authentication(), row.get().login(), now);
    }

    @Override
    public void deleteById(String id) {
        if (SessionIds.isId(id)) {
            jdbc.sql("DELETE FROM auth_session WHERE session_id_hash = :hash")
                    .param("hash", SessionIds.sha256(id))
                    .update();
        }
    }

    @Override
    public int revokeAll(UUID accountId) {
        return jdbc.sql("DELETE FROM auth_session WHERE account_id = :accountId")
                .param("accountId", accountId.toString())
                .update();
    }

    public Optional<LoginProgress> consumeLogin(String id, String stateHash) {
        if (!SessionIds.isId(id) || stateHash == null || !SessionIds.isHash(stateHash)) {
            return Optional.empty();
        }
        String hash = SessionIds.sha256(id);
        Instant now = clock.instant();
        return transactions.execute(status -> {
            Optional<LoginProgress> login = select(hash)
                    .filter(row -> row.expiresAt().isAfter(now))
                    .map(Row::login);
            if (login.isEmpty() || !login.get().stateHash().equals(stateHash)
                    || !now.isBefore(login.get().startedAt().plus(AuthSession.LOGIN_VALIDITY))) {
                return Optional.empty();
            }
            int cleared = jdbc.sql("""
                            UPDATE auth_session
                            SET login_state_hash = NULL, login_nonce = NULL, login_code_verifier = NULL,
                                login_return_path = NULL, login_started_at = NULL
                            WHERE session_id_hash = :hash AND login_state_hash = :stateHash
                            """)
                    .param("hash", hash)
                    .param("stateHash", stateHash)
                    .update();
            return cleared == 1 ? login : Optional.<LoginProgress>empty();
        });
    }

    public int deleteExpired() {
        return jdbc.sql("DELETE FROM auth_session WHERE expires_at <= :now")
                .param("now", timestamp(clock.instant()))
                .update();
    }

    private Optional<Row> select(String hash) {
        return jdbc.sql("""
                        SELECT account_id, authenticated_at, created_at, last_used_at, expires_at,
                               login_state_hash, login_nonce, login_code_verifier, login_return_path, login_started_at
                        FROM auth_session WHERE session_id_hash = :hash
                        """)
                .param("hash", hash)
                .query((rs, rowNum) -> new Row(instant(rs, "created_at"), instant(rs, "last_used_at"),
                        instant(rs, "expires_at"), authentication(rs), login(rs)))
                .optional();
    }

    private void insert(AuthSession session) {
        SessionAuthentication authentication = session.authentication();
        LoginProgress login = session.login();
        jdbc.sql("""
                        INSERT INTO auth_session (session_id_hash, account_id, authenticated_at, created_at,
                            last_used_at, expires_at, login_state_hash, login_nonce, login_code_verifier,
                            login_return_path, login_started_at)
                        VALUES (:hash, :accountId, :authenticatedAt, :createdAt, :lastUsedAt, :expiresAt,
                            :stateHash, :nonce, :codeVerifier, :returnPath, :startedAt)
                        """)
                .param("hash", SessionIds.sha256(session.getId()))
                .param("accountId", authentication == null ? null : authentication.accountId().toString())
                .param("authenticatedAt", authentication == null ? null : timestamp(authentication.authenticatedAt()))
                .param("createdAt", timestamp(session.getCreationTime()))
                .param("lastUsedAt", timestamp(session.getLastAccessedTime()))
                .param("expiresAt", timestamp(session.expiresAt()))
                .param("stateHash", login == null ? null : login.stateHash())
                .param("nonce", login == null ? null : login.nonce())
                .param("codeVerifier", login == null ? null : login.codeVerifier())
                .param("returnPath", login == null ? null : login.returnPath())
                .param("startedAt", login == null ? null : timestamp(login.startedAt()))
                .update();
    }

    private boolean update(AuthSession session) {
        String hash = session.persistedHash();
        if (session.idChanged()) {
            String newHash = SessionIds.sha256(session.getId());
            int moved = jdbc.sql("UPDATE auth_session SET session_id_hash = :newHash WHERE session_id_hash = :hash")
                    .param("newHash", newHash)
                    .param("hash", hash)
                    .update();
            if (moved == 0) {
                return false;
            }
            hash = newHash;
        }
        if (session.authenticationChanged()) {
            SessionAuthentication authentication = session.authentication();
            jdbc.sql("""
                            UPDATE auth_session SET account_id = :accountId, authenticated_at = :authenticatedAt
                            WHERE session_id_hash = :hash
                            """)
                    .param("accountId", authentication == null ? null : authentication.accountId().toString())
                    .param("authenticatedAt",
                            authentication == null ? null : timestamp(authentication.authenticatedAt()))
                    .param("hash", hash)
                    .update();
        }
        if (session.loginChanged()) {
            LoginProgress login = session.login();
            jdbc.sql("""
                            UPDATE auth_session
                            SET login_state_hash = :stateHash, login_nonce = :nonce,
                                login_code_verifier = :codeVerifier, login_return_path = :returnPath,
                                login_started_at = :startedAt
                            WHERE session_id_hash = :hash
                            """)
                    .param("stateHash", login == null ? null : login.stateHash())
                    .param("nonce", login == null ? null : login.nonce())
                    .param("codeVerifier", login == null ? null : login.codeVerifier())
                    .param("returnPath", login == null ? null : login.returnPath())
                    .param("startedAt", login == null ? null : timestamp(login.startedAt()))
                    .param("hash", hash)
                    .update();
        }
        if (session.touchDue() || session.authenticationChanged()) {
            int touched = jdbc.sql("""
                            UPDATE auth_session SET last_used_at = :lastUsedAt, expires_at = :expiresAt
                            WHERE session_id_hash = :hash
                            """)
                    .param("lastUsedAt", timestamp(session.getLastAccessedTime()))
                    .param("expiresAt", timestamp(session.expiresAt()))
                    .param("hash", hash)
                    .update();
            return touched == 1;
        }
        return true;
    }

    private void enforceLimit(UUID accountId) {
        List<String> stale = jdbc.sql("""
                        SELECT session_id_hash FROM auth_session WHERE account_id = :accountId
                        ORDER BY created_at DESC, session_id_hash
                        """)
                .param("accountId", accountId.toString())
                .query(String.class)
                .list();
        for (String hash : stale.subList(Math.min(properties.maxPerAccount(), stale.size()), stale.size())) {
            jdbc.sql("DELETE FROM auth_session WHERE session_id_hash = :hash").param("hash", hash).update();
        }
    }

    private void deleteHash(String hash, Instant now) {
        jdbc.sql("DELETE FROM auth_session WHERE session_id_hash = :hash AND expires_at <= :now")
                .param("hash", hash)
                .param("now", timestamp(now))
                .update();
    }

    private static SessionAuthentication authentication(ResultSet rs) throws SQLException {
        String accountId = rs.getString("account_id");
        if (accountId == null) {
            return null;
        }
        return new SessionAuthentication(UUID.fromString(accountId), instant(rs, "authenticated_at"));
    }

    private static LoginProgress login(ResultSet rs) throws SQLException {
        String stateHash = rs.getString("login_state_hash");
        if (stateHash == null) {
            return null;
        }
        return new LoginProgress(stateHash, rs.getString("login_nonce"), rs.getString("login_code_verifier"),
                rs.getString("login_return_path"), instant(rs, "login_started_at"));
    }

    private record Row(Instant createdAt, Instant lastUsedAt, Instant expiresAt,
            SessionAuthentication authentication, LoginProgress login) {
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime timestamp(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

}
