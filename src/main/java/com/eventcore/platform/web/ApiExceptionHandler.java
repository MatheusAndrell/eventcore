package com.eventcore.platform.web;

import com.eventcore.platform.json.InvalidJsonException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidJsonException.class)
    ProblemDetail invalidJson(InvalidJsonException exception, HttpServletRequest request) {
        return withCorrelation(Problems.of(HttpStatus.BAD_REQUEST, "malformed-json", "Malformed JSON",
                exception.getMessage()), request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        log.error("unhandled exception while processing {} {}", request.getMethod(), request.getRequestURI(),
                exception);
        return withCorrelation(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "the request could not be processed"), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        List<String> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail problem = Problems.withViolations(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                "the request body has invalid fields", violations);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && request instanceof NativeWebRequest nativeRequest
                && nativeRequest.getNativeRequest() instanceof HttpServletRequest servletRequest) {
            withCorrelation(problem, servletRequest);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail withCorrelation(ProblemDetail problem, HttpServletRequest request) {
        CorrelationIdFilter.find(request).ifPresent(id -> problem.setProperty("correlationId", id.value()));
        return problem;
    }
}
