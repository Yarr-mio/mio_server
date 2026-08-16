package com.mio.ai.memory.consolidation;

import com.mio.ai.domain.UserMemoryPreference;
import com.mio.ai.repository.UserMemoryPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 메모리 수집·활용 동의 조회 (이슈 #453).
 *
 * <p>장기 기억을 만드는 모든 경로(SessionConsolidator·WeeklyReflectionJob 등)가 같은
 * 판정을 공유한다. 선호 행이 없는 사용자는 기본 동의 상태다
 * (user_memory_preferences.memory_retention_agreed 기본값과 동일).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryConsentChecker {

    private final UserMemoryPreferenceRepository preferenceRepository;

    /**
     * 조회 실패 시 <b>적재하지 않는다</b>(fail-closed) — 철회한 사용자의 기억을 만드는
     * 쪽이 기억 한 번을 건너뛰는 쪽보다 나쁜 실패다.
     */
    public boolean isRetentionAllowed(UUID userId) {
        try {
            return preferenceRepository.findByUserId(userId)
                    .map(UserMemoryPreference::isMemoryRetentionAgreed)
                    .orElse(true);
        } catch (Exception e) {
            log.error("MemoryConsentChecker: consent lookup failed, treating as withdrawn userId={}", userId, e);
            return false;
        }
    }
}
