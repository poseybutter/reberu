package reberu.core.support;

public interface FailureStreaks {

    void check(RateLimit rule, String key);

    void failed(RateLimit rule, String key);

    void succeeded(RateLimit rule, String key);

}
