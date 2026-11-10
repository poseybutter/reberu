package reberu.core.support;

public interface RateLimits {

    void acquire(RateLimit rule, String key);

    static String ipKey(String address) {
        return "ip:" + address;
    }

    static String accountKey(Object accountId) {
        return "account:" + accountId;
    }

}
