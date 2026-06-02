package com.mio.ai.crisis;

import java.util.UUID;

/**
 * Crisis flow 발동 시 발행. SafetyProfileBuilder가 즉시 profile 캐시를 invalidate (§17.8).
 */
public record CrisisDetectedEvent(
        UUID sessionId,
        UUID userId,
        int severity
) {}
