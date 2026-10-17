package dev.cutpay.web;

import org.springframework.http.HttpStatus;

/** A business error that maps to a structured JSON error response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;
    private final String param;

    public ApiException(HttpStatus status, String type, String code, String message, String param) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.param = param;
    }

    public static ApiException invalidRequest(String code, String message, String param) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", code, message, param);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "authentication_error", code, message, null);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "permission_error", code, message, null);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "invalid_request_error", code, message, null);
    }

    public static ApiException conflict(String code, String message, String param) {
        return new ApiException(HttpStatus.CONFLICT, "invalid_request_error", code, message, param);
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }

    public String param() {
        return param;
    }
}
