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
import reberu.core.tenancy.entity.TenancyCodes.InvitationState;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;

@Entity
@Owned(Ownership.ORG)
@Table(name = "invitation")
public class Invitation {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "team_id", updatable = false)
    private UUID teamId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MembershipKind kind;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private TeamRole role;

    @Column(name = "guest_days", updatable = false)
    private Integer guestDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationState state;

    @Column(name = "accept_by", nullable = false)
    private Instant acceptBy;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected Invitation() {
    }

    public static Invitation create(UUID id, UUID orgId, UUID teamId, String email, MembershipKind kind,
            TeamRole role, Integer guestDays, InvitationState state, Instant acceptBy, UUID createdBy, Instant at) {
        if (state != InvitationState.PENDING && state != InvitationState.PENDING_APPROVAL) {
            throw new IllegalArgumentException("초대 시작 상태 오류: " + state);
        }
        Invitation invitation = new Invitation();
        invitation.id = id;
        invitation.orgId = orgId;
        invitation.teamId = teamId;
        invitation.email = email;
        invitation.kind = kind;
        invitation.role = role;
        invitation.guestDays = guestDays;
        invitation.state = state;
        invitation.acceptBy = acceptBy;
        invitation.createdBy = createdBy;
        invitation.createdAt = at;
        invitation.updatedAt = at;
        return invitation;
    }

    public boolean open(Instant now) {
        return state == InvitationState.PENDING_APPROVAL
                || state == InvitationState.PENDING && now.isBefore(acceptBy);
    }

    public void approve(UUID actorId, Instant acceptBy, Instant at) {
        require(InvitationState.PENDING_APPROVAL);
        state = InvitationState.PENDING;
        approvedBy = actorId;
        this.acceptBy = acceptBy;
        updatedAt = at;
    }

    public void revoke(Instant at) {
        if (state != InvitationState.PENDING_APPROVAL && state != InvitationState.PENDING) {
            throw new IllegalStateException("취소할 수 없는 초대 상태: " + state);
        }
        state = InvitationState.REVOKED;
        updatedAt = at;
    }

    private void require(InvitationState expected) {
        if (state != expected) {
            throw new IllegalStateException("초대 상태 오류: " + state);
        }
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

    public String email() {
        return email;
    }

    public MembershipKind kind() {
        return kind;
    }

    public TeamRole role() {
        return role;
    }

    public Integer guestDays() {
        return guestDays;
    }

    public InvitationState state() {
        return state;
    }

    public Instant acceptBy() {
        return acceptBy;
    }

}
