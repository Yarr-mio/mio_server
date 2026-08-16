package com.mio.memorycontrol.dto;

import java.util.UUID;

/** 기억 정정·비활성화 결과 (이슈 #453). */
public record MemoryUpdateResponse(
        UUID id,
        String type,
        String status
) {}
