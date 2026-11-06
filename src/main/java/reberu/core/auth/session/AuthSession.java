package reberu.core.auth.session;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.session.Session;

public final class AuthSession implements Session {

    public static final String AUTHENTICATION = "reberu.authentication";
    public static final String LOGIN = "reberu.login";

    static final Duration LOGIN_VALIDITY = Duration.ofMinutes(10);
    static final Duration ANONYMOUS_LIFETIME = Duration.ofMinutes(15);
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final SessionProperties properties;
    private final Instant createdAt;
    private String persistedHash;
    private String id;
    private Instant lastUsedAt;
    private Instant persistedLastUsedAt;
    private SessionAuthentication authentication;
    private LoginProgress login;
    private boolean authenticationChanged;
    private boolean loginChanged;
    private Instant now;

    private AuthSession(SessionProperties properties, String id, String persistedHash, Instant createdAt,
            Instant lastUsedAt, SessionAuthentication authentication, LoginProgress login, Instant now) {
        this.properties = properties;
        this.id = id;
        this.persistedHash = persistedHash;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.persistedLastUsedAt = persistedHash == null ? null : lastUsedAt;
        this.authentication = authentication;
        this.login = login;
        this.now = now;
    }

    static AuthSession create(SessionProperties properties, Instant now) {
        Instant at = micros(now);
        return new AuthSession(properties, SessionIds.newId(), null, at, at, null, null, at);
    }

    static AuthSession load(SessionProperties properties, String id, Instant createdAt, Instant lastUsedAt,
            SessionAuthentication authentication, LoginProgress login, Instant now) {
        return new AuthSession(properties, id, SessionIds.sha256(id), createdAt, lastUsedAt, authentication, login,
                micros(now));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String changeSessionId() {
        id = SessionIds.newId();
        return id;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String name) {
        return switch (name) {
            case AUTHENTICATION -> (T) authentication;
            case LOGIN -> (T) (loginValid() ? login : null);
            default -> null;
        };
    }

    @Override
    public Set<String> getAttributeNames() {
        Set<String> names = new LinkedHashSet<>();
        if (authentication != null) {
            names.add(AUTHENTICATION);
        }
        if (loginValid()) {
            names.add(LOGIN);
        }
        return names;
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            removeAttribute(name);
            return;
        }
        switch (name) {
            case AUTHENTICATION -> {
                if (!(value instanceof SessionAuthentication next)) {
                    throw rejected(name);
                }
                authentication = new SessionAuthentication(next.accountId(), micros(next.authenticatedAt()));
                authenticationChanged = true;
            }
            case LOGIN -> {
                if (!(value instanceof LoginProgress next)) {
                    throw rejected(name);
                }
                login = new LoginProgress(next.stateHash(), next.nonce(), next.codeVerifier(), next.returnPath(),
                        micros(next.startedAt()));
                loginChanged = true;
            }
            default -> throw rejected(name);
        }
    }

    @Override
    public void removeAttribute(String name) {
        switch (name) {
            case AUTHENTICATION -> {
                authentication = null;
                authenticationChanged = true;
            }
            case LOGIN -> {
                login = null;
                loginChanged = true;
            }
            default -> {
            }
        }
    }

    @Override
    public Instant getCreationTime() {
        return createdAt;
    }

    @Override
    public void setLastAccessedTime(Instant lastAccessedTime) {
        Instant at = micros(lastAccessedTime);
        if (at.isAfter(lastUsedAt)) {
            lastUsedAt = at;
        }
        if (at.isAfter(now)) {
            now = at;
        }
    }

    @Override
    public Instant getLastAccessedTime() {
        return lastUsedAt;
    }

    @Override
    public void setMaxInactiveInterval(Duration interval) {
        throw new UnsupportedOperationException("세션 만료 시간은 설정 값으로만 정함");
    }

    @Override
    public Duration getMaxInactiveInterval() {
        return authentication == null ? ANONYMOUS_LIFETIME : properties.idleTimeout();
    }

    @Override
    public boolean isExpired() {
        return !now.isBefore(expiresAt());
    }

    Instant expiresAt() {
        if (authentication == null) {
            return createdAt.plus(ANONYMOUS_LIFETIME);
        }
        Instant idle = lastUsedAt.plus(properties.idleTimeout());
        Instant max = createdAt.plus(properties.maxLifetime());
        return idle.isBefore(max) ? idle : max;
    }

    SessionAuthentication authentication() {
        return authentication;
    }

    LoginProgress login() {
        return login;
    }

    String persistedHash() {
        return persistedHash;
    }

    boolean isNew() {
        return persistedHash == null;
    }

    boolean idChanged() {
        return persistedHash != null && !persistedHash.equals(SessionIds.sha256(id));
    }

    boolean authenticationChanged() {
        return authenticationChanged;
    }

    boolean loginChanged() {
        return loginChanged;
    }

    boolean touchDue() {
        return persistedLastUsedAt == null
                || !lastUsedAt.isBefore(persistedLastUsedAt.plus(TOUCH_INTERVAL));
    }

    void markSaved() {
        persistedHash = SessionIds.sha256(id);
        authenticationChanged = false;
        loginChanged = false;
        persistedLastUsedAt = lastUsedAt;
    }

    private boolean loginValid() {
        return login != null && now.isBefore(login.startedAt().plus(LOGIN_VALIDITY));
    }

    private static IllegalArgumentException rejected(String name) {
        return new IllegalArgumentException("세션에 저장할 수 없는 값: " + name);
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

}
