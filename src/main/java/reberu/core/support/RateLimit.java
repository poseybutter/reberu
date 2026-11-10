package reberu.core.support;

public enum RateLimit {

    LOGIN("SEC-RATE-01", Key.IP),
    INVITE_LINK("SEC-RATE-02", Key.IP),
    INVITATION("SEC-RATE-03", Key.ACCOUNT),
    JOIN_REQUEST("SEC-RATE-04", Key.ACCOUNT),
    ORG_CREATE("SEC-RATE-05", Key.ACCOUNT),
    PUBLIC_ORG_SEARCH("SEC-RATE-06", Key.ACCOUNT),
    POSTING("SEC-RATE-07", Key.ACCOUNT),
    DEFAULT("SEC-RATE-08", Key.ACCOUNT);

    private final String id;
    private final Key key;

    RateLimit(String id, Key key) {
        this.id = id;
        this.key = key;
    }

    public String id() {
        return id;
    }

    public Key key() {
        return key;
    }

    public enum Key {
        IP,
        ACCOUNT
    }

}
