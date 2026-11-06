package reberu.core.support.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import reberu.core.support.ErrorCode;
import reberu.core.support.ErrorResponse;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.support.TraceIds;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handle(Exception exception, HttpServletRequest request) throws Exception {
        if (exception instanceof AuthenticationException || exception instanceof AccessDeniedException) {
            throw exception;
        }
        ReberuException resolved = resolve(exception);
        if (resolved.code() == ErrorCode.INTERNAL_ERROR) {
            log.error("처리하지 못한 예외", exception);
        }
        ErrorResponse body = ErrorResponse.of(resolved.code(), TraceIds.current(request), resolved.fields());
        return ResponseEntity.status(resolved.code().status()).body(body);
    }

    private static ReberuException resolve(Exception exception) {
        if (exception instanceof ReberuException reberu) {
            return reberu;
        }
        if (exception instanceof BindException bind) {
            return invalid(bindViolations(bind));
        }
        if (exception instanceof HandlerMethodValidationException validation) {
            return invalid(methodViolations(validation));
        }
        if (exception instanceof ConstraintViolationException validation) {
            return invalid(validation.getConstraintViolations().stream()
                    .map(violation -> FieldViolations.of(FieldViolations.lastNode(violation.getPropertyPath()), violation))
                    .toList());
        }
        if (exception instanceof HttpMessageNotReadableException
                || exception instanceof HttpMediaTypeException
                || exception instanceof TypeMismatchException
                || exception instanceof ServletRequestBindingException
                || exception instanceof MissingServletRequestPartException) {
            return invalid(List.of());
        }
        if (exception instanceof NoResourceFoundException
                || exception instanceof NoHandlerFoundException
                || exception instanceof HttpRequestMethodNotSupportedException) {
            return new ReberuException(ErrorCode.NOT_FOUND);
        }
        if (exception instanceof MaxUploadSizeExceededException) {
            return new ReberuException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        return new ReberuException(ErrorCode.INTERNAL_ERROR);
    }

    private static List<FieldViolation> bindViolations(BindException bind) {
        List<FieldViolation> violations = new ArrayList<>();
        for (FieldError error : bind.getFieldErrors()) {
            ConstraintViolation<?> violation = error.contains(ConstraintViolation.class)
                    ? error.unwrap(ConstraintViolation.class)
                    : null;
            violations.add(FieldViolations.of(error.getField(), violation));
        }
        return violations;
    }

    private static List<FieldViolation> methodViolations(HandlerMethodValidationException validation) {
        List<FieldViolation> violations = new ArrayList<>();
        validation.getParameterValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> {
                ConstraintViolation<?> violation = result.unwrap(error, ConstraintViolation.class);
                violations.add(FieldViolations.of(field, violation));
            });
        });
        return violations;
    }

    private static ReberuException invalid(List<FieldViolation> fields) {
        Map<String, FieldViolation> byField = new LinkedHashMap<>();
        for (FieldViolation violation : fields) {
            byField.merge(violation.field(), violation,
                    (kept, next) -> next.reason().ordinal() < kept.reason().ordinal() ? next : kept);
        }
        return new ReberuException(ErrorCode.INVALID_REQUEST, List.copyOf(byField.values()));
    }

}
