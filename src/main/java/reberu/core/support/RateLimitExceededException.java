package reberu.core.support;

import java.time.Duration;

public class RateLimitExceededException extends ReberuException {

    private final RateLimit rule;
    private final Duration retryAfter;

    public RateLimitExceededException(RateLimit rule, Duration retryAfter) {
        super(ErrorCode.RATE_LIMITED);
        this.rule = rule;
        this.retryAfter = retryAfter;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    public RateLimit rule() {
        return rule;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public String retryAfterSeconds() {
        long seconds = (retryAfter.toMillis() + 999) / 1000;
        return Long.toString(Math.max(1, seconds));
    }

}
