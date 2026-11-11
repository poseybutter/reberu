package reberu.core.tenancy.entity;

public final class TenancyCodes {

    private TenancyCodes() {
    }

    public enum Visibility {
        PRIVATE,
        PUBLIC
    }

    public enum OrgState {
        ACTIVE,
        SUSPENDED,
        ARCHIVED,
        DELETION_SCHEDULED,
        DELETED
    }

    public enum JoinRequestMode {
        OFF,
        APPROVAL,
        INSTANT
    }

    public enum MembershipKind {
        MEMBER,
        GUEST
    }

    public enum MembershipState {
        REQUESTED,
        ACTIVE,
        REJECTED,
        SUSPENDED,
        LEFT,
        EXPIRED
    }

    public enum JoinedVia {
        CREATOR,
        BOOTSTRAP,
        INVITATION,
        LINK,
        REQUEST,
        DOMAIN,
        DIRECT
    }

    public enum TeamState {
        ACTIVE,
        ARCHIVED
    }

    public enum TeamJoinMode {
        APPROVAL,
        INSTANT
    }

    public enum CustomFieldTarget {
        TASK,
        PROJECT
    }

    public enum CustomFieldKind {
        TEXT,
        MEMO
    }

    public enum TeamRole {
        TEAM_ADMIN,
        TEAM_MEMBER,
        VIEWER
    }

}
