package reberu.core.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        String traceId,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldViolation> fields
) {

    public static ErrorResponse of(ErrorCode code, String traceId, List<FieldViolation> fields) {
        List<FieldViolation> body = code == ErrorCode.INVALID_REQUEST ? List.copyOf(fields) : null;
        return new ErrorResponse(code.name(), code.message(), traceId, body);
    }

}
