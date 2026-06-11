package com.mio.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(ErrorDetail error) {

    public static ErrorResponse of(ErrorCode errorCode, String traceId) {
        return new ErrorResponse(new ErrorDetail(errorCode.getCode(), errorCode.getMessage(), traceId));
    }

    public static ErrorResponse of(ErrorCode errorCode, String customMessage, String traceId) {
        return new ErrorResponse(new ErrorDetail(errorCode.getCode(), customMessage, traceId));
    }

    public record ErrorDetail(
            String code,
            String message,
            String traceId
    ) {}
}
