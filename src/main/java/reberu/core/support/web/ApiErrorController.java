package reberu.core.support.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.support.ErrorCode;
import reberu.core.support.ErrorResponse;
import reberu.core.support.TraceIds;

@RestController
class ApiErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        ErrorCode code = codeFor(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        if (code == ErrorCode.INTERNAL_ERROR
                && request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable cause) {
            log.error("처리하지 못한 예외", cause);
        }
        ErrorResponse body = ErrorResponse.of(code, TraceIds.current(request), List.of());
        return ResponseEntity.status(code.status()).body(body);
    }

    private static ErrorCode codeFor(Object status) {
        if (!(status instanceof Integer value)) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return switch (value) {
            case 400 -> ErrorCode.INVALID_REQUEST;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN_ACTION;
            case 404, 405 -> ErrorCode.NOT_FOUND;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }

}
