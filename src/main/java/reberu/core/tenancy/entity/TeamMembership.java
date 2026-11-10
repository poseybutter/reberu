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
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;

@Entity
@Owned(Ownership.TEAM)
@Table(name = "team_membership")
public class TeamMembership {

    @Id
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TeamRole role;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected TeamMembership() {
    }

    public static TeamMembership active(UUID id, UUID teamId, UUID accountId, TeamRole role, JoinedVia joinedVia,
            Instant at) {
        TeamMembership membership = new TeamMembership();
        membership.id = id;
        membership.teamId = teamId;
        membership.accountId = accountId;
        membership.role = role;
        membership.state = MembershipState.ACTIVE;
        membership.joinedVia = joinedVia;
        membership.createdAt = at;
        membership.updatedAt = at;
        return membership;
    }

    public void promoteToAdmin(Instant at) {
        if (state != MembershipState.ACTIVE || role == TeamRole.TEAM_ADMIN) {
            throw new IllegalStateException("팀 관리자로 올릴 수 없는 멤버십");
        }
        role = TeamRole.TEAM_ADMIN;
        updatedAt = at;
    }

    public void reject(Instant at) {
        if (state != MembershipState.REQUESTED) {
            throw new IllegalStateException("거절할 수 없는 멤버십 상태: " + state);
        }
        state = MembershipState.REJECTED;
        updatedAt = at;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public UUID teamId() {
        return teamId;
    }

    public UUID accountId() {
        return accountId;
    }

    public TeamRole role() {
        return role;
    }

    public MembershipState state() {
        return state;
    }

}
