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

    public static <T> ApiResponse<T> ok(T data, Meta meta) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .meta(meta)
                .build();
    }

public record Meta(
            String traceId,
            String nextCursor,
            Boolean hasMore
    ) {
        public static Meta trace(String traceId) {
            return new Meta(traceId, null, null);
        }

        public static Meta page(String traceId, String nextCursor, boolean hasMore) {
            return new Meta(traceId, nextCursor, hasMore);
        }
    }
}
