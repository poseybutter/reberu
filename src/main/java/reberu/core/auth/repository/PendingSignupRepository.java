package reberu.core.auth.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import reberu.core.account.GoogleProfile;

@Repository
public class PendingSignupRepository {

    private final JdbcClient jdbc;

    PendingSignupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String sessionIdHash, GoogleProfile profile, Instant createdAt, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO auth_pending_signup (session_id_hash, google_sub, email, name, picture_url,
                            workspace_domain, created_at, expires_at)
                        VALUES (:hash, :sub, :email, :name, :pictureUrl, :workspaceDomain, :createdAt, :expiresAt)
                        """)
                .param("hash", sessionIdHash)
                .param("sub", profile.sub())
                .param("email", profile.email())
                .param("name", profile.name())
                .param("pictureUrl", profile.pictureUrl())
                .param("workspaceDomain", profile.workspaceDomain())
                .param("createdAt", timestamp(createdAt))
                .param("expiresAt", timestamp(expiresAt))
                .update();
    }

    public Optional<GoogleProfile> find(String sessionIdHash, Instant now) {
        return jdbc.sql("""
                        SELECT google_sub, email, name, picture_url, workspace_domain FROM auth_pending_signup
                        WHERE session_id_hash = :hash AND expires_at > :now
                        """)
                .param("hash", sessionIdHash)
                .param("now", timestamp(now))
                .query((rs, row) -> new GoogleProfile(rs.getString("google_sub"), rs.getString("email"),
                        rs.getString("name"), rs.getString("picture_url"), rs.getString("workspace_domain")))
                .optional();
    }

    public boolean delete(String sessionIdHash) {
        return jdbc.sql("DELETE FROM auth_pending_signup WHERE session_id_hash = :hash")
                .param("hash", sessionIdHash)
                .update() == 1;
    }

    public int deleteExpired(Instant now) {
        return jdbc.sql("DELETE FROM auth_pending_signup WHERE expires_at <= :now")
                .param("now", timestamp(now))
                .update();
    }

    private static LocalDateTime timestamp(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

}
