package com.mio.ai.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisDetectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * SafetyProfile 빌더 — Phase 3-5 완성 (§17).
 * - 5개 쿼리 병렬 ~25ms
 * - risk_prior_score 기반 dynamic_thresholds 계산
 * - 7일/10세션 이후 personalized 전환
 * - CrisisDetectedEvent 시 즉시 invalidate
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SafetyProfileBuilder {

    private static final String PROFILE_KEY = "session:%s:safety_profile";
    private static final Duration PROFILE_TTL = Duration.ofMinutes(90);

    // 보수적 default — 신규 사용자 (모든 임계값을 민감한 쪽으로)
    private static final Map<String, Double> DEFAULT_THRESHOLDS = Map.of(
            "emotion_drop_threshold", 30.0,
            "repetitive_negative_count", 3.0,
            "message_burst_count", 10.0,
            "burst_window_minutes", 5.0
    );

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final Executor profileBuildPool = Executors.newVirtualThreadPerTaskExecutor();

    // ── 진입점 ────────────────────────────────────────────────────

    /** 하위 호환 — sessionId 없는 경우 default만 반환 */
    public SafetyProfile getOrDefault(String userId) {
        return buildDefault(userId);
    }

    /**
     * ConversationOrchestrator가 매 메시지마다 호출.
     * Redis cache HIT → JSON 역직렬화 즉시 반환 (0.5ms).
     * MISS → buildSync 동기 build.
     */
    public SafetyProfile getOrDefault(String sessionId, String userId) {
        try {
            String json = redisTemplate.opsForValue().get(PROFILE_KEY.formatted(sessionId));
            if (json != null) {
                log.debug("SafetyProfileBuilder: getOrDefault cache HIT sessionId={}", sessionId);
                return objectMapper.readValue(json, SafetyProfile.class);
            }
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder: getOrDefault cache read failed", e);
        }
        // MISS → 동기 build (사실상 세션 시작 직후라 pre-warm이 완료됐어야 함)
        return buildSync(userId);
    }

    /**
     * ContextPreWarmer용 — 세션 시작 시 profile 빌드 후 JSON으로 Redis 캐싱.
     */
    public SafetyProfile buildAndCache(String sessionId, String userId) {
        String key = PROFILE_KEY.formatted(sessionId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("SafetyProfileBuilder: buildAndCache HIT for sessionId={}", sessionId);
                return objectMapper.readValue(cached, SafetyProfile.class);
            }
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder: Redis read failed, building fresh", e);
        }

        SafetyProfile profile = buildSync(userId);
        cacheProfile(key, profile);
        return profile;
    }

    public SafetyProfile getFromCache(String sessionId, String userId) {
        try {
            String json = redisTemplate.opsForValue().get(PROFILE_KEY.formatted(sessionId));
            if (json != null) {
                return objectMapper.readValue(json, SafetyProfile.class);
            }
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder: cache read failed, using default", e);
        }
        return buildDefault(userId);
    }

    private void cacheProfile(String key, SafetyProfile profile) {
        try {
            String json = objectMapper.writeValueAsString(profile);
            redisTemplate.opsForValue().set(key, json, PROFILE_TTL);
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder: Redis set failed for key={}", key, e);
        }
    }

    public void invalidate(String sessionId) {
        try {
            redisTemplate.delete(PROFILE_KEY.formatted(sessionId));
            log.debug("SafetyProfileBuilder: invalidated profile for sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder.invalidate failed for sessionId={}", sessionId, e);
        }
    }

    @EventListener
    public void onCrisisDetected(CrisisDetectedEvent event) {
        invalidate(event.sessionId().toString());
    }

    // ── 빌드 로직 ────────────────────────────────────────────────

    SafetyProfile buildDefault(String userId) {
        return new SafetyProfile(
                userId, SafetyProfile.SOURCE_DEFAULT,
                DEFAULT_THRESHOLDS,
                List.of(), List.of(), List.of(),
                0.0, 0, List.of()
        );
    }

    /**
     * 5개 쿼리 병렬 빌드 (~25ms).
     * 데이터 부족 시 default 반환.
     */
    SafetyProfile buildSync(String userId) {
        UUID userUUID;
        try {
            userUUID = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return buildDefault(userId);
        }

        try {
            // 5개 병렬 쿼리
            var beliefsF   = CompletableFuture.supplyAsync(() -> queryActiveBeliefs(userUUID), profileBuildPool);
            var crisisF    = CompletableFuture.supplyAsync(() -> queryRecentCrisis(userUUID), profileBuildPool);
            var patternsF  = CompletableFuture.supplyAsync(() -> queryCbtPatterns(userUUID), profileBuildPool);
            var outcomesF  = CompletableFuture.supplyAsync(() -> queryInterventionOutcomes(userUUID), profileBuildPool);
            var sessionMF  = CompletableFuture.supplyAsync(() -> querySessionMeta(userUUID), profileBuildPool);

            CompletableFuture.allOf(beliefsF, crisisF, patternsF, outcomesF, sessionMF).join();

            var beliefs     = beliefsF.join();
            var crisisMax   = crisisF.join();
            var patterns    = patternsF.join();
            var outcomes    = outcomesF.join();
            var sessionMeta = sessionMF.join();

            // 데이터 없으면 default
            if (!beliefs.hasData() && patterns.topDistortionCodes().isEmpty()) {
                return buildDefault(userId);
            }

            // personalized 전환 조건: 7일 이상 사용 OR 10세션 이상
            boolean personalized = sessionMeta.daysSinceFirst() >= 7 || sessionMeta.totalSessions() >= 10;
            String source = personalized ? SafetyProfile.SOURCE_PERSONALIZED : SafetyProfile.SOURCE_DEFAULT;

            // risk_prior_score 계산
            double riskPrior = Math.min(1.0,
                    (crisisMax * 0.3) + (beliefs.negativeCount() * 0.1));

            // dynamic_thresholds — high-prior 사용자는 민감하게
            Map<String, Double> thresholds = computeThresholds(riskPrior);

            List<String> policyFlags = new ArrayList<>();
            if (riskPrior > 0.5) policyFlags.add("dependency_caution");
            if (crisisMax >= 2)  policyFlags.add("force_judge");

            return new SafetyProfile(
                    userId, source, thresholds,
                    outcomes.effectiveKinds(),
                    outcomes.ineffectiveKinds(),
                    policyFlags,
                    riskPrior, crisisMax,
                    patterns.topDistortionCodes(),
                    beliefs.negativeCount(),
                    beliefs.copingStyle(),
                    patterns.triggerKinds(),
                    "sensitive"
            );
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder.buildSync failed for userId={}, falling back to default", userId, e);
            return buildDefault(userId);
        }
    }

    // ── DB 쿼리 ──────────────────────────────────────────────────

    private BeliefSummary queryActiveBeliefs(UUID userId) {
        try {
            var rows = jdbcTemplate.queryForList(
                    """
                    SELECT belief_kind, polarity, confidence
                    FROM user_beliefs
                    WHERE user_id = ? AND status = 'active'
                    ORDER BY confidence DESC LIMIT 10
                    """, userId
            );
            int negCount = (int) rows.stream()
                    .filter(r -> "negative".equals(r.get("polarity"))).count();
            String coping = negCount > 2 ? "avoidance" : null;
            return new BeliefSummary(negCount, coping, !rows.isEmpty());
        } catch (Exception e) {
            log.warn("queryActiveBeliefs failed", e);
            return new BeliefSummary(0, null, false);
        }
    }

    private int queryRecentCrisis(UUID userId) {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    """
                    SELECT COALESCE(MAX(severity), 0)
                    FROM crisis_events
                    WHERE user_id = ?
                      AND created_at > NOW() - INTERVAL '14 days'
                    """, Integer.class, userId
            );
            return max != null ? max : 0;
        } catch (Exception e) {
            log.warn("queryRecentCrisis failed", e);
            return 0;
        }
    }

    private PatternSummary queryCbtPatterns(UUID userId) {
        try {
            var rows = jdbcTemplate.queryForList(
                    """
                    SELECT pattern_type, recurrence_count
                    FROM cbt_patterns
                    WHERE user_id = ?
                    ORDER BY recurrence_count DESC LIMIT 5
                    """, userId
            );
            List<String> codes = rows.stream()
                    .map(r -> (String) r.get("pattern_type"))
                    .toList();
            List<String> triggers = codes.stream().limit(2).toList();
            return new PatternSummary(codes, triggers);
        } catch (Exception e) {
            log.warn("queryCbtPatterns failed", e);
            return new PatternSummary(List.of(), List.of());
        }
    }

    private OutcomeSummary queryInterventionOutcomes(UUID userId) {
        try {
            var rows = jdbcTemplate.queryForList(
                    """
                    SELECT intervention_kind, AVG(delta) AS avg_delta, COUNT(*) AS cnt
                    FROM intervention_outcomes
                    WHERE user_id = ?
                      AND created_at > NOW() - INTERVAL '30 days'
                    GROUP BY intervention_kind
                    HAVING COUNT(*) >= 2
                    ORDER BY AVG(delta) DESC
                    LIMIT 10
                    """, userId
            );
            List<String> effective = new ArrayList<>();
            List<String> ineffective = new ArrayList<>();
            for (var row : rows) {
                String kind = (String) row.get("intervention_kind");
                // AVG(delta)는 PostgreSQL numeric → BigDecimal 반환 가능, Number로 안전 캐스트
                Number avgNum = (Number) row.get("avg_delta");
                Double avg = avgNum != null ? avgNum.doubleValue() : null;
                if (avg != null && avg > 5) effective.add(kind);
                else if (avg != null && avg < -3) ineffective.add(kind);
            }
            return new OutcomeSummary(effective, ineffective);
        } catch (Exception e) {
            log.warn("queryInterventionOutcomes failed", e);
            return new OutcomeSummary(List.of(), List.of());
        }
    }

    private SessionMeta querySessionMeta(UUID userId) {
        try {
            var row = jdbcTemplate.queryForMap(
                    """
                    SELECT COUNT(*) AS total_sessions,
                           EXTRACT(DAY FROM NOW() - MIN(started_at)) AS days_since_first
                    FROM sessions WHERE user_id = ?
                    """, userId
            );
            long total = ((Number) row.get("total_sessions")).longValue();
            double days = row.get("days_since_first") != null
                    ? ((Number) row.get("days_since_first")).doubleValue() : 0;
            return new SessionMeta((int) total, (int) days);
        } catch (Exception e) {
            log.warn("querySessionMeta failed", e);
            return new SessionMeta(0, 0);
        }
    }

    // ── threshold 계산 ────────────────────────────────────────────

    private Map<String, Double> computeThresholds(double riskPrior) {
        Map<String, Double> t = new HashMap<>(DEFAULT_THRESHOLDS);
        if (riskPrior > 0.4) {
            // high-prior → 더 민감하게 (낮은 임계값)
            t.put("emotion_drop_threshold", 25.0);
            t.put("repetitive_negative_count", 2.0);
            t.put("message_burst_count", 8.0);
        }
        return Map.copyOf(t);
    }

    // ── 내부 DTO ──────────────────────────────────────────────────

    private record BeliefSummary(int negativeCount, String copingStyle, boolean hasData) {}
    private record PatternSummary(List<String> topDistortionCodes, List<String> triggerKinds) {}
    private record OutcomeSummary(List<String> effectiveKinds, List<String> ineffectiveKinds) {}
    private record SessionMeta(int totalSessions, int daysSinceFirst) {}
}
