package reberu.core.support;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "입력 값을 확인해 주세요"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요"),
    CONSENT_REQUIRED(HttpStatus.UNAUTHORIZED, "약관 동의가 필요해요"),
    REAUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "요청을 확인할 수 없어요. 새로고침해 주세요"),
    FORBIDDEN_ACTION(HttpStatus.FORBIDDEN, "이 작업을 할 수 없어요"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없어요"),
    READ_ONLY(HttpStatus.CONFLICT, "보관된 곳이라 바꿀 수 없어요"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 사람이 먼저 바꿨어요. 새로 불러와 주세요"),
    REPORT_LOCKED(HttpStatus.CONFLICT, "편집 마감이 지났어요"),
    LIMIT_EXCEEDED(HttpStatus.CONFLICT, "더 만들 수 없어요"),
    ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 요청이에요"),
    LAST_ADMIN_REQUIRED(HttpStatus.CONFLICT, "관리자가 한 명은 있어야 해요"),
    REQUEST_COOLDOWN(HttpStatus.CONFLICT, "잠시 뒤에 다시 요청해 주세요"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "보낸 내용이 너무 커요"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 뒤에 다시 해 주세요"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "잠시 후 다시 시도해 주세요");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

}
