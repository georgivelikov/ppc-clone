package dev.cutpay.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Error body: {@code {"error": {"type": ..., "code": ..., "message": ..., "param": ...}}}. */
public record ApiError(Body error) {

    public static ApiError of(String type, String code, String message, String param, String requestId) {
        return new ApiError(new Body(type, code, message, param, requestId));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(
            String type,
            String code,
            String message,
            String param,
            @JsonProperty("request_id") String requestId) {
    }
}
