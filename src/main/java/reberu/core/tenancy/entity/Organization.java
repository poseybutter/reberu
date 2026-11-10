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
import reberu.core.tenancy.entity.TenancyCodes.JoinRequestMode;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;

@Entity
@Owned(Ownership.ORG)
@Table(name = "organization")
public class Organization {

    public static final int NAME_MIN = 2;
    public static final int NAME_MAX = 40;
    public static final int TAGLINE_MAX = 100;
    public static final int TIME_ZONE_MAX = 64;

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String tagline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgState state;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "join_link_enabled", nullable = false)
    private boolean joinLinkEnabled;

    @Column(name = "member_admission_review", nullable = false)
    private boolean memberAdmissionReview;

    @Enumerated(EnumType.STRING)
    @Column(name = "join_request_mode", nullable = false)
    private JoinRequestMode joinRequestMode;

    @Column(name = "guest_approval_required", nullable = false)
    private boolean guestApprovalRequired;

    @Column(name = "guest_default_days", nullable = false)
    private int guestDefaultDays;

    @Column(name = "guest_max_days", nullable = false)
    private int guestMaxDays;

    @Column(name = "guest_orgspace_allowed", nullable = false)
    private boolean guestOrgspaceAllowed;

    @Column(name = "invitation_ttl_days", nullable = false)
    private int invitationTtlDays;

    @Column(nullable = false)
    private boolean ownerless;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_before_suspend")
    private OrgState stateBeforeSuspend;

    @Column(name = "deletion_scheduled_at")
    private Instant deletionScheduledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected Organization() {
    }

    public static Organization create(UUID id, String name, String tagline, Visibility visibility, String timeZone,
            UUID createdBy, Instant at) {
        Organization organization = new Organization();
        organization.id = id;
        organization.name = name;
        organization.tagline = tagline;
        organization.visibility = visibility;
        organization.state = OrgState.ACTIVE;
        organization.timeZone = timeZone;
        organization.joinLinkEnabled = false;
        organization.memberAdmissionReview = true;
        organization.joinRequestMode = JoinRequestMode.OFF;
        organization.guestApprovalRequired = true;
        organization.guestDefaultDays = 30;
        organization.guestMaxDays = 90;
        organization.guestOrgspaceAllowed = false;
        organization.invitationTtlDays = 7;
        organization.ownerless = false;
        organization.createdBy = createdBy;
        organization.createdAt = at;
        organization.updatedAt = at;
        return organization;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Visibility visibility() {
        return visibility;
    }

    public OrgState state() {
        return state;
    }

    public JoinRequestMode joinRequestMode() {
        return joinRequestMode;
    }

    public boolean joinLinkEnabled() {
        return joinLinkEnabled;
    }

    public boolean memberAdmissionReview() {
        return memberAdmissionReview;
    }

    public boolean guestApprovalRequired() {
        return guestApprovalRequired;
    }

    public boolean guestOrgspaceAllowed() {
        return guestOrgspaceAllowed;
    }

    public int guestMaxDays() {
        return guestMaxDays;
    }

    public UUID createdBy() {
        return createdBy;
    }

}
