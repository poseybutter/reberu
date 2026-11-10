package reberu.core.access;

public enum AccessLevel {

    PUBLIC(Scope.NONE),
    SIGNUP_PENDING(Scope.NONE),
    ACCOUNT(Scope.NONE),
    ORG_MEMBER(Scope.ORG),
    ORG_ADMIN(Scope.ORG),
    TEAM_VIEWER(Scope.TEAM),
    TEAM_MEMBER(Scope.TEAM),
    TEAM_ADMIN(Scope.TEAM);

    private final Scope scope;

    AccessLevel(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }

    public enum Scope {
        NONE,
        ORG,
        TEAM
    }

}
