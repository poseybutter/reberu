package reberu.core.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record IdempotentRequest(UUID accountId, String key, String method, String path, Object body) {

    public static final String HEADER = "Idempotency-Key";

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public IdempotentRequest {
        Objects.requireNonNull(accountId, "계정 ID 없음");
        Objects.requireNonNull(method, "요청 메서드 없음");
        Objects.requireNonNull(path, "요청 경로 없음");
        key = normalize(key);
    }

    public static IdempotentRequest of(UUID accountId, String key, HttpServletRequest request, Object body) {
        return new IdempotentRequest(accountId, key, request.getMethod(), request.getRequestURI(), body);
    }

    private static String normalize(String key) {
        if (key == null || key.isBlank()) {
            throw invalid(FieldReason.REQUIRED);
        }
        if (!UUID_TEXT.matcher(key).matches()) {
            throw invalid(FieldReason.INVALID_FORMAT);
        }
        return key.toLowerCase(Locale.ROOT);
    }

    private static ReberuException invalid(FieldReason reason) {
        return new ReberuException(ErrorCode.INVALID_REQUEST, List.of(new FieldViolation(HEADER, reason)));
    }

}
