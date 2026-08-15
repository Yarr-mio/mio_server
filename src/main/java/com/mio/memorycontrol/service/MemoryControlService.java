package com.mio.memorycontrol.service;

import com.mio.memorycontrol.dto.MemoryConsentWithdrawResponse;
import com.mio.memorycontrol.dto.MemoryListResponse;
import com.mio.memorycontrol.dto.MemoryUpdateRequest;
import com.mio.memorycontrol.dto.MemoryUpdateResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 사용자 장기 기억 통제 — 조회·정정·비활성화·동의 철회 (이슈 #453, 로드맵 §12 P0-6).
 */
@Service
public class MemoryControlService {

    public MemoryListResponse listMemories(UUID userId, int page, int size) {
        throw new UnsupportedOperationException("not implemented yet — issue #453");
    }

    public MemoryUpdateResponse updateMemory(UUID userId, UUID memoryId, MemoryUpdateRequest request) {
        throw new UnsupportedOperationException("not implemented yet — issue #453");
    }

    public MemoryConsentWithdrawResponse withdrawConsent(UUID userId) {
        throw new UnsupportedOperationException("not implemented yet — issue #453");
    }
}
