package com.mio.common.error;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(ErrorDetail error) {

    public static ErrorResponse of(ErrorCode errorCode, String traceId) {
        return new ErrorResponse(new ErrorDetail(errorCode.getCode(), errorCode.getMessage(), traceId));
    }

    public record ErrorDetail(
            String code,
            String message,
            @JsonProperty("trace_id") String traceId
    ) {}
}
