package modules.controller;

import jakarta.servlet.http.HttpServletRequest;

import modules.common.exception.BusinessRuleException;
import modules.common.exception.ForbiddenException;
import modules.common.exception.NotFoundException;
import modules.common.exception.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps the exceptions of {@code org.example.common.exception} (and invalid input) to HTTP status codes, with the
 * message as an RFC 9457 problem detail. Each error is logged at WARN with the request.
 */
@RestControllerAdvice
public class HttpErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpErrorHandler.class);

    @ExceptionHandler(UnauthenticatedException.class)
    ProblemDetail unauthenticated(UnauthenticatedException e, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, e, request);
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException e, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, e, request);
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, e, request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, e, request);
    }

    /**
     * Missing or invalid values: {@code Text.require} and {@code Objects.requireNonNull} in commands and entities,
     * unreadable JSON, a malformed UUID.
     */
    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail badRequest(Exception e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, e, request);
    }

    private static ProblemDetail problem(HttpStatus status, Exception e, HttpServletRequest request) {
        log.warn("{} {} -> {} {}: {}", request.getMethod(), request.getRequestURI(), status.value(),
                status.getReasonPhrase(), e.getMessage());
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }
}
