package com.mio.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        Object error,
        Meta meta
) {
    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .meta(new Meta(traceId))
                .build();
    }

    public record Meta(String traceId) {}
}
