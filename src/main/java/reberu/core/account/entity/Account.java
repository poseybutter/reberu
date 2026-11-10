package reberu.core.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import reberu.core.account.AccountState;
import reberu.core.account.GoogleProfile;
import reberu.core.support.Owned;
import reberu.core.support.Owned.Ownership;

@Entity
@Owned(Ownership.ACCOUNT)
@Table(name = "account")
public class Account {

    public static final int DISPLAY_NAME_MAX = 30;

    @Id
    private UUID id;

    @Column(name = "google_sub", nullable = false, updatable = false)
    private String googleSub;

    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "display_name_custom", nullable = false)
    private boolean displayNameCustom;

    @Column(name = "picture_url")
    private String pictureUrl;

    @Column(name = "workspace_domain")
    private String workspaceDomain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountState state;

    @Column(name = "default_avatar")
    private String defaultAvatar;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public static Account create(UUID id, GoogleProfile profile, Instant at) {
        Account account = new Account();
        account.id = id;
        account.googleSub = profile.sub();
        account.state = AccountState.ACTIVE;
        account.displayNameCustom = false;
        account.createdAt = at;
        account.applyProfile(profile, at);
        return account;
    }

    public void recordLogin(GoogleProfile profile, Instant at) {
        if (state != AccountState.ACTIVE) {
            throw new IllegalStateException("로그인 정보를 갱신할 수 없는 계정 상태: " + state);
        }
        applyProfile(profile, at);
    }

    private void applyProfile(GoogleProfile profile, Instant at) {
        email = profile.email();
        if (!displayNameCustom) {
            displayName = initialDisplayName(profile);
        }
        pictureUrl = profile.pictureUrl();
        workspaceDomain = profile.workspaceDomain();
        lastLoginAt = at;
        updatedAt = at;
    }

    static String initialDisplayName(GoogleProfile profile) {
        String name = profile.name() == null ? "" : profile.name().strip();
        if (name.isEmpty()) {
            int at = profile.email().indexOf('@');
            name = (at > 0 ? profile.email().substring(0, at) : profile.email()).strip();
        }
        return name.codePointCount(0, name.length()) <= DISPLAY_NAME_MAX
                ? name
                : name.substring(0, name.offsetByCodePoints(0, DISPLAY_NAME_MAX));
    }

    public UUID id() {
        return id;
    }

    public AccountState state() {
        return state;
    }

    public String displayName() {
        return displayName;
    }

}
