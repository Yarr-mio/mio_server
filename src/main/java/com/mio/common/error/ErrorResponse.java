package com.mio.common.error;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        boolean success,
        Object data,
        ErrorDetail error,
        Meta meta
) {
    public static ErrorResponse of(ErrorCode errorCode, String traceId) {
        return ErrorResponse.builder()
                .success(false)
                .data(null)
                .error(new ErrorDetail(errorCode.getCode(), errorCode.getMessage()))
                .meta(new Meta(traceId))
                .build();
    }

    public record ErrorDetail(String code, String message) {}

    public record Meta(@JsonProperty("trace_id") String traceId) {}
}
