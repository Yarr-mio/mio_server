package com.mio.ai.memory.working;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 3-2 Working Memory — Redis 세션 버퍼.
 *
 * 키 구조:
 *   session:{id}:messages  → List (최근 10턴 = 20개, LPUSH 최신 우선)
 *   session:{id}:working   → Hash (socratic_count, distortion:*, risk_accumulation)
 *   session:{id}:beliefs   → Set  (activatedBeliefIds)
 *   session:{id}:triggers  → Set  (currentSessionTriggers)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkingMemory {

    private static final int MAX_MESSAGES = 20; // 10턴 = user + assistant × 10

    private static final String MESSAGES_KEY  = "session:%s:messages";
    private static final String WORKING_KEY   = "session:%s:working";
    private static final String BELIEFS_KEY   = "session:%s:beliefs";
    private static final String TRIGGERS_KEY  = "session:%s:triggers";

    private static final String FIELD_SOCRATIC_COUNT    = "socratic_count";
    private static final String FIELD_RISK_ACCUMULATION = "risk_accumulation";
    private static final String FIELD_DISTORTION_PREFIX = "distortion:";

    private static final Duration SESSION_TTL = Duration.ofMinutes(90);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // ── 메시지 버퍼 ─────────────────────────────────────────────

    public void appendMessage(UUID sessionId, String role, String content) {
        try {
            String key = messagesKey(sessionId);
            String json = serialize(new WorkingMessage(role, content, System.currentTimeMillis()));
            if (json == null) return;

            redisTemplate.opsForList().leftPush(key, json);
            redisTemplate.opsForList().trim(key, 0, MAX_MESSAGES - 1);
            redisTemplate.expire(key, SESSION_TTL);
        } catch (Exception e) {
            log.warn("WorkingMemory.appendMessage failed for sessionId={} — skipping", sessionId, e);
        }
    }

    public List<WorkingMessage> getRecentMessages(UUID sessionId) {
        try {
            List<String> raw = redisTemplate.opsForList().range(messagesKey(sessionId), 0, MAX_MESSAGES - 1);
            if (raw == null || raw.isEmpty()) return Collections.emptyList();

            List<WorkingMessage> messages = new ArrayList<>();
            for (String json : raw) {
                WorkingMessage msg = deserialize(json);
                if (msg != null) messages.add(msg);
            }
            // LPUSH 순서는 최신이 앞 → 오래된 순서로 뒤집어 반환
            Collections.reverse(messages);
            return messages;
        } catch (Exception e) {
            log.warn("WorkingMemory.getRecentMessages failed for sessionId={} — returning empty", sessionId, e);
            return Collections.emptyList();
        }
    }

    // ── CBT 카운터 ───────────────────────────────────────────────

    public int getSocraticQuestionCount(UUID sessionId) {
        String value = (String) redisTemplate.opsForHash().get(workingKey(sessionId), FIELD_SOCRATIC_COUNT);
        return parseIntSafe(value);
    }

    public void incrementSocraticQuestionCount(UUID sessionId) {
        String k = workingKey(sessionId);
        redisTemplate.opsForHash().increment(k, FIELD_SOCRATIC_COUNT, 1);
        redisTemplate.expire(k, SESSION_TTL);
    }

    public int getDistortionCount(UUID sessionId, String distortionCode) {
        String value = (String) redisTemplate.opsForHash()
                .get(workingKey(sessionId), FIELD_DISTORTION_PREFIX + distortionCode);
        return parseIntSafe(value);
    }

    public void incrementDistortionCount(UUID sessionId, String distortionCode) {
        String k = workingKey(sessionId);
        redisTemplate.opsForHash().increment(k, FIELD_DISTORTION_PREFIX + distortionCode, 1);
        redisTemplate.expire(k, SESSION_TTL);
    }

    // ── 리스크 누적 ──────────────────────────────────────────────

    public void incrementRiskAccumulation(UUID sessionId) {
        String k = workingKey(sessionId);
        redisTemplate.opsForHash().increment(k, FIELD_RISK_ACCUMULATION, 1);
        redisTemplate.expire(k, SESSION_TTL);
    }

    // ── 활성 신념 / 트리거 태그 ───────────────────────────────────

    public void addActivatedBeliefId(UUID sessionId, String beliefId) {
        String k = beliefsKey(sessionId);
        redisTemplate.opsForSet().add(k, beliefId);
        redisTemplate.expire(k, SESSION_TTL);
    }

    public void addSessionTrigger(UUID sessionId, String triggerTag) {
        String k = triggersKey(sessionId);
        redisTemplate.opsForSet().add(k, triggerTag);
        redisTemplate.expire(k, SESSION_TTL);
    }

    // ── 전체 조회 ────────────────────────────────────────────────

    public SessionDelta getSessionDelta(UUID sessionId) {
        Map<Object, Object> workingEntries = redisTemplate.opsForHash().entries(workingKey(sessionId));
        Set<String> beliefIds = redisTemplate.opsForSet().members(beliefsKey(sessionId));
        Set<String> triggers  = redisTemplate.opsForSet().members(triggersKey(sessionId));

        int socraticCount = 0;
        int riskAccumulation = 0;
        Map<String, Integer> distortionCounts = new HashMap<>();

        for (Map.Entry<Object, Object> entry : workingEntries.entrySet()) {
            String field = (String) entry.getKey();
            String value = (String) entry.getValue();

            if (FIELD_SOCRATIC_COUNT.equals(field)) {
                socraticCount = parseIntSafe(value);
            } else if (FIELD_RISK_ACCUMULATION.equals(field)) {
                riskAccumulation = parseIntSafe(value);
            } else if (field.startsWith(FIELD_DISTORTION_PREFIX)) {
                String code = field.substring(FIELD_DISTORTION_PREFIX.length());
                distortionCounts.put(code, parseIntSafe(value));
            }
        }

        return new SessionDelta(
                socraticCount,
                distortionCounts,
                riskAccumulation,
                beliefIds != null ? beliefIds : Set.of(),
                triggers  != null ? triggers  : Set.of()
        );
    }

    // ── 세션 종료 ─────────────────────────────────────────────────

    public void clear(UUID sessionId) {
        try {
            redisTemplate.delete(List.of(
                    messagesKey(sessionId),
                    workingKey(sessionId),
                    beliefsKey(sessionId),
                    triggersKey(sessionId)
            ));
        } catch (Exception e) {
            log.warn("WorkingMemory.clear failed for sessionId={} — TTL will expire keys", sessionId, e);
        }
    }

    // ── 키 빌더 ──────────────────────────────────────────────────

    private String messagesKey(UUID sessionId)  { return MESSAGES_KEY.formatted(sessionId); }
    private String workingKey(UUID sessionId)   { return WORKING_KEY.formatted(sessionId); }
    private String beliefsKey(UUID sessionId)   { return BELIEFS_KEY.formatted(sessionId); }
    private String triggersKey(UUID sessionId)  { return TRIGGERS_KEY.formatted(sessionId); }

    // ── 직렬화 ───────────────────────────────────────────────────

    private String serialize(WorkingMessage msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            log.warn("WorkingMemory: failed to serialize message", e);
            return null;
        }
    }

    private WorkingMessage deserialize(String json) {
        try {
            return objectMapper.readValue(json, WorkingMessage.class);
        } catch (Exception e) {
            log.warn("WorkingMemory: failed to deserialize message len={}", json == null ? 0 : json.length(), e);
            return null;
        }
    }

    private int parseIntSafe(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("WorkingMemory: unexpected non-integer value '{}', defaulting to 0", value);
            return 0;
        }
    }
}
