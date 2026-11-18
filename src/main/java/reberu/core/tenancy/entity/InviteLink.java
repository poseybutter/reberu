package reberu.core.tenancy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import reberu.core.support.Owned;
import reberu.core.support.Owned.Ownership;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;

@Entity
@Owned(Ownership.TEAM)
@Table(name = "invite_link")
public class InviteLink {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "approval_required", nullable = false, updatable = false)
    private boolean approvalRequired;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TeamRole role;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "max_uses", nullable = false, updatable = false)
    private int maxUses;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected InviteLink() {
    }

    public static InviteLink create(UUID id, UUID orgId, UUID teamId, String tokenHash, boolean approvalRequired,
            TeamRole role, Instant expiresAt, int maxUses, UUID createdBy, Instant at) {
        if (role != TeamRole.TEAM_MEMBER && role != TeamRole.VIEWER) {
            throw new IllegalArgumentException("초대 링크 역할 오류: " + role);
        }
        if (maxUses < 1 || !expiresAt.isAfter(at)) {
            throw new IllegalArgumentException("초대 링크 기한·횟수 오류");
        }
        InviteLink link = new InviteLink();
        link.id = id;
        link.orgId = orgId;
        link.teamId = teamId;
        link.tokenHash = tokenHash;
        link.approvalRequired = approvalRequired;
        link.role = role;
        link.expiresAt = expiresAt;
        link.maxUses = maxUses;
        link.enabled = true;
        link.createdBy = createdBy;
        link.createdAt = at;
        link.updatedAt = at;
        return link;
    }

    public void disable(Instant at) {
        if (!enabled) {
            throw new IllegalStateException("이미 꺼진 초대 링크");
        }
        enabled = false;
        updatedAt = at;
    }

    public UUID id() {
        return id;
    }

    public UUID orgId() {
        return orgId;
    }

    public UUID teamId() {
        return teamId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public boolean approvalRequired() {
        return approvalRequired;
    }

    public TeamRole role() {
        return role;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public int maxUses() {
        return maxUses;
    }

    public int useCount() {
        return useCount;
    }

    public boolean enabled() {
        return enabled;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

}
