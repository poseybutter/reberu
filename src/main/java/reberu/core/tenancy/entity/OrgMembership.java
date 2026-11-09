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
import reberu.core.tenancy.entity.TenancyCodes.JoinedVia;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;

@Entity
@Table(name = "org_membership")
public class OrgMembership {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipKind kind;

    @Column(name = "org_admin", nullable = false)
    private boolean orgAdmin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "joined_via", nullable = false, updatable = false)
    private JoinedVia joinedVia;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "left_reason")
    private String leftReason;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected OrgMembership() {
    }

    public static OrgMembership activeAdmin(UUID id, UUID orgId, UUID accountId, JoinedVia joinedVia, Instant at) {
        OrgMembership membership = new OrgMembership();
        membership.id = id;
        membership.orgId = orgId;
        membership.accountId = accountId;
        membership.kind = MembershipKind.MEMBER;
        membership.orgAdmin = true;
        membership.state = MembershipState.ACTIVE;
        membership.joinedVia = joinedVia;
        membership.createdAt = at;
        membership.updatedAt = at;
        return membership;
    }

    public boolean canBecomeAdmin() {
        return state == MembershipState.ACTIVE && kind == MembershipKind.MEMBER && !orgAdmin;
    }

    public void grantAdmin(Instant at) {
        if (!canBecomeAdmin()) {
            throw new IllegalStateException("조직 관리자로 지정할 수 없는 멤버십");
        }
        orgAdmin = true;
        updatedAt = at;
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public MembershipKind kind() {
        return kind;
    }

    public boolean orgAdmin() {
        return orgAdmin;
    }

    public MembershipState state() {
        return state;
    }

    public JoinedVia joinedVia() {
        return joinedVia;
    }

}
