package dev.cutpay.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Translates exceptions into the platform's error format. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException e) {
        // JSON whatever the client asked for: a stream client (Accept: text/event-stream) must still get the error.
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(e.type(), e.code(), e.getMessage(), e.param(), requestId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        var fieldError = e.getBindingResult().getFieldError();
        String param = fieldError != null ? toSnakeCase(fieldError.getField()) : null;
        String message = fieldError != null ? fieldError.getDefaultMessage() : "Invalid request";
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request_error", "validation_failed", message, param, requestId()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request_error", "malformed_body", "Request body is not valid JSON", null,
                        requestId()));
    }

    /** Keeps the status of exceptions thrown with an explicit one (e.g. a 502 from a failed upstream call). */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> handleStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        String type = e.getStatusCode().is5xxServerError() ? "api_error" : "invalid_request_error";
        String code = status != null ? status.name().toLowerCase(java.util.Locale.ROOT) : "error";
        return ResponseEntity.status(e.getStatusCode())
                .body(ApiError.of(type, code, e.getReason() != null ? e.getReason() : code, null, requestId()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("api_error", "internal_error", "Something went wrong on our side", null,
                        requestId()));
    }

    private static String requestId() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }

    private static String toSnakeCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}
