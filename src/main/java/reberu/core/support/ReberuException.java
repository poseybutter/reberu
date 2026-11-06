package reberu.core.support;

import java.util.List;

public class ReberuException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldViolation> fields;

    public ReberuException(ErrorCode code) {
        this(code, List.of());
    }

    public ReberuException(ErrorCode code, List<FieldViolation> fields) {
        super(code.name());
        this.code = code;
        this.fields = List.copyOf(fields);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldViolation> fields() {
        return fields;
    }

}
