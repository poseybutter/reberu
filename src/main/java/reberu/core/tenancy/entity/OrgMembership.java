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
import reberu.core.support.Owned;
import reberu.core.support.Owned.Ownership;
import reberu.core.tenancy.entity.TenancyCodes.JoinedVia;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;

@Entity
@Owned(Ownership.ORG)
@Table(name = "org_membership")
public class OrgMembership {

    public static final String LEFT_REMOVED = "REMOVED";

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

    public static OrgMembership invited(UUID id, UUID orgId, UUID accountId, MembershipKind kind, Instant expiresAt,
            Instant at) {
        OrgMembership membership = new OrgMembership();
        membership.id = id;
        membership.orgId = orgId;
        membership.accountId = accountId;
        membership.kind = kind;
        membership.state = MembershipState.ACTIVE;
        membership.joinedVia = JoinedVia.INVITATION;
        membership.expiresAt = kind == MembershipKind.GUEST ? expiresAt : null;
        membership.createdAt = at;
        membership.updatedAt = at;
        return membership;
    }

    public static OrgMembership linked(UUID id, UUID orgId, UUID accountId, boolean requested, Instant at) {
        OrgMembership membership = new OrgMembership();
        membership.id = id;
        membership.orgId = orgId;
        membership.accountId = accountId;
        membership.kind = MembershipKind.MEMBER;
        membership.state = requested ? MembershipState.REQUESTED : MembershipState.ACTIVE;
        membership.joinedVia = JoinedVia.LINK;
        membership.createdAt = at;
        membership.updatedAt = at;
        return membership;
    }

    public void request(Instant at) {
        if (state != MembershipState.LEFT && state != MembershipState.REJECTED && state != MembershipState.EXPIRED) {
            throw new IllegalStateException("요청으로 바꿀 수 없는 멤버십 상태: " + state);
        }
        kind = MembershipKind.MEMBER;
        state = MembershipState.REQUESTED;
        orgAdmin = false;
        expiresAt = null;
        leftReason = null;
        updatedAt = at;
    }

    public boolean expiredGuest(Instant now) {
        return state == MembershipState.ACTIVE && kind == MembershipKind.GUEST && expiresAt != null
                && !now.isBefore(expiresAt);
    }

    public boolean admittable(Instant now) {
        return state == MembershipState.REQUESTED || state == MembershipState.LEFT
                || state == MembershipState.REJECTED || state == MembershipState.EXPIRED || expiredGuest(now);
    }

    public void admit(MembershipKind kind, Instant expiresAt, Instant at) {
        if (!admittable(at)) {
            throw new IllegalStateException("초대로 들일 수 없는 멤버십 상태: " + state);
        }
        this.kind = kind;
        state = MembershipState.ACTIVE;
        orgAdmin = false;
        this.expiresAt = kind == MembershipKind.GUEST ? expiresAt : null;
        leftReason = null;
        updatedAt = at;
    }

    public void extendGuest(Instant expiresAt, Instant at) {
        if (state != MembershipState.ACTIVE || kind != MembershipKind.GUEST || expiredGuest(at)) {
            throw new IllegalStateException("만료 시각을 늘릴 수 없는 멤버십");
        }
        if (this.expiresAt == null || expiresAt.isAfter(this.expiresAt)) {
            this.expiresAt = expiresAt;
            updatedAt = at;
        }
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

    public boolean current() {
        return state == MembershipState.ACTIVE || state == MembershipState.SUSPENDED;
    }

    public void revokeAdmin(Instant at) {
        if (!orgAdmin) {
            throw new IllegalStateException("조직 관리자가 아닌 멤버십");
        }
        orgAdmin = false;
        updatedAt = at;
    }

    public void suspend(Instant at) {
        if (state != MembershipState.ACTIVE) {
            throw new IllegalStateException("정지할 수 없는 멤버십 상태: " + state);
        }
        state = MembershipState.SUSPENDED;
        updatedAt = at;
    }

    public void unsuspend(Instant at) {
        if (state != MembershipState.SUSPENDED) {
            throw new IllegalStateException("정지 해제할 수 없는 멤버십 상태: " + state);
        }
        state = MembershipState.ACTIVE;
        updatedAt = at;
    }

    public void remove(Instant at) {
        if (!current()) {
            throw new IllegalStateException("내보낼 수 없는 멤버십 상태: " + state);
        }
        state = MembershipState.LEFT;
        leftReason = LEFT_REMOVED;
        orgAdmin = false;
        updatedAt = at;
    }

    public void reject(Instant at) {
        if (state != MembershipState.REQUESTED) {
            throw new IllegalStateException("거절할 수 없는 멤버십 상태: " + state);
        }
        state = MembershipState.REJECTED;
        rejectedAt = at;
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

    public Instant expiresAt() {
        return expiresAt;
    }

}
