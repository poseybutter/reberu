package reberu.core.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

public final class TraceIds {

    public static final String MDC_KEY = "traceId";
    public static final String ATTRIBUTE = TraceIds.class.getName() + ".traceId";

    private TraceIds() {
    }

    public static String current(HttpServletRequest request) {
        Object traceId = request.getAttribute(ATTRIBUTE);
        return traceId instanceof String value ? value : newTraceId();
    }

    public static String newTraceId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }

}
