package com.mio.memorycontrol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 사용자 장기 기억 목록 (이슈 #453). */
public record MemoryListResponse(
        List<MemoryItemResponse> items,
        int page,
        int size,
        @JsonProperty("total_elements") long totalElements
) {}
