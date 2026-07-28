package com.mio.ai.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisDetectedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
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

    // 신규 사용자 기본값
    private static final Map<String, Double> DEFAULT_THRESHOLDS = Map.of(
            "emotion_drop_threshold", 30.0,
            "repetitive_negative_count", 3.0,
            "message_burst_count", 10.0,
            "burst_window_minutes", 5.0
    );

    // high-prior 사용자 또는 위험도를 확인하지 못한 경우 — 더 낮은 임계값으로 일찍 반응한다.
    private static final Map<String, Double> SENSITIVE_THRESHOLDS = Map.of(
            "emotion_drop_threshold", 25.0,
            "repetitive_negative_count", 2.0,
            "message_burst_count", 8.0,
            "burst_window_minutes", 5.0
    );

    /**
     * 프로파일 빌드 결과. {@code outcome=degraded} 는 근거 조회에 실패해 보수적 기본값으로
     * 채운 경우다 (이슈 #261).
     *
     * <p>이 값이 치솟으면 <b>전체 트래픽에 InputJudge 호출이 한 번씩 더 붙는다</b>. 위기 이력을
     * 확인하지 못한 이상 누가 고위험 사용자인지 알 수 없어 전원에게 {@code force_judge} 를 붙이기
     * 때문이다. 보호를 잃지 않는 방향을 택한 대가이므로, 운영이 그 상태를 즉시 볼 수 있어야 한다.
     */
    private static final String PROFILE_BUILD_METRIC = "mio.safety_profile.builds";

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

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
        return getWithCacheHit(sessionId, userId).profile();
    }

    /**
     * profile + cache HIT 여부를 함께 반환 — trace 로깅용 (§27.6).
     */
    public ProfileResult getWithCacheHit(String sessionId, String userId) {
        try {
            String json = redisTemplate.opsForValue().get(PROFILE_KEY.formatted(sessionId));
            if (json != null) {
                log.debug("SafetyProfileBuilder: cache HIT sessionId={}", sessionId);
                SafetyProfile profile = objectMapper.readValue(json, SafetyProfile.class);
                return new ProfileResult(profile, true);
            }
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder: cache read failed", e);
        }
        return new ProfileResult(buildSync(userId), false);
    }

    public record ProfileResult(SafetyProfile profile, boolean cacheHit) {}

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
     * 안전 신호를 확인하지 못한 채 만든 프로파일 (이슈 #261).
     *
     * <p>{@link #buildDefault} 와 값이 같아 보이지만 성격이 다르다. default 는 "이력이 없는
     * 신규 사용자"이고 이쪽은 "이력을 조회하지 못한 사용자"다. 후자는 최근 severity 3 위기를
     * 겪었을 수도 있으므로 {@code force_judge} 와 민감 임계값을 붙인다.
     */
    SafetyProfile buildDegraded(String userId) {
        return new SafetyProfile(
                userId, SafetyProfile.SOURCE_DEFAULT,
                SENSITIVE_THRESHOLDS,
                List.of(), List.of(), List.of("force_judge"),
                0.0, 0, List.of(),
                0, null, List.of(), "sensitive",
                true
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

            var beliefs       = beliefsF.join();
            var crisisHistory = crisisF.join();
            var patterns      = patternsF.join();
            var outcomes      = outcomesF.join();
            var sessionMeta   = sessionMF.join();

            int crisisMax = crisisHistory.maxSeverity();
            // 위기 이력을 확인하지 못한 상태. 위험 없음이 아니라 "모름"이므로 보수적으로 간다.
            boolean degraded = !crisisHistory.resolved();

            // 데이터 없으면 default. 단 위기 이력 조회가 실패했다면 그 사실이 사라지면 안 된다.
            if (!beliefs.hasData() && patterns.topDistortionCodes().isEmpty()) {
                recordBuildOutcome(degraded);
                return degraded ? buildDegraded(userId) : buildDefault(userId);
            }

            // personalized 전환 조건: 7일 이상 사용 OR 10세션 이상
            boolean personalized = sessionMeta.daysSinceFirst() >= 7 || sessionMeta.totalSessions() >= 10;
            String source = personalized ? SafetyProfile.SOURCE_PERSONALIZED : SafetyProfile.SOURCE_DEFAULT;

            // risk_prior_score 계산
            double riskPrior = Math.min(1.0,
                    (crisisMax * 0.3) + (beliefs.negativeCount() * 0.1));

            // dynamic_thresholds — high-prior 사용자는 민감하게.
            // 이력을 모르는 상태도 민감한 쪽으로 둔다.
            Map<String, Double> thresholds = computeThresholds(riskPrior, degraded);

            List<String> policyFlags = new ArrayList<>();
            if (riskPrior > 0.5) policyFlags.add("dependency_caution");
            // 이력이 실제로 없는 사용자에게는 InputJudge 호출이 조금 늘 뿐이지만,
            // 이력이 있는 사용자는 조회 실패에도 보호를 잃지 않는다.
            if (crisisMax >= 2 || degraded) policyFlags.add("force_judge");

            recordBuildOutcome(degraded);
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
                    "sensitive",
                    degraded
            );
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder.buildSync failed for userId={}, falling back to degraded", userId, e);
            // 병렬 쿼리가 통째로 실패한 경우다. 위기 이력을 포함해 아무것도 확인하지 못했으므로
            // 평범한 default 보다 보수적인 프로파일을 쓴다.
            recordBuildOutcome(true);
            return buildDegraded(userId);
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

    /**
     * 최근 14일 위기 이력 조회 (이슈 #261).
     *
     * <p>실패 시 {@code 0} 을 반환하면 "위기 이력 없음"과 같은 값이 되어, DB 일시 장애가
     * "이 사용자는 위기 이력이 없다"로 해석된다. 최근 severity 3 위기를 겪은 사용자가 장애 중에
     * 가장 둔감한 설정으로 대화하게 되는 방향이라 조회 여부를 값에 담는다.
     */
    private CrisisHistory queryRecentCrisis(UUID userId) {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    """
                    SELECT COALESCE(MAX(severity), 0)
                    FROM crisis_events
                    WHERE user_id = ?
                      AND created_at > NOW() - INTERVAL '14 days'
                    """, Integer.class, userId
            );
            return CrisisHistory.resolved(max != null ? max : 0);
        } catch (Exception e) {
            log.warn("queryRecentCrisis failed", e);
            return CrisisHistory.unresolved();
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

    /** DB 조회를 실제로 수행한 빌드에서만 기록한다 — 캐시 HIT 나 단순 default 반환은 세지 않는다. */
    private void recordBuildOutcome(boolean degraded) {
        meterRegistry.counter(PROFILE_BUILD_METRIC, "outcome", degraded ? "degraded" : "resolved")
                .increment();
    }

    // ── threshold 계산 ────────────────────────────────────────────

    /**
     * @param forceSensitive riskPrior 를 신뢰할 수 없을 때 민감한 쪽으로 강제한다.
     *                       riskPrior 는 위기 이력에서 계산되는데, 그 조회가 실패하면 값이 0 이라
     *                       "위험 없음"과 구별되지 않는다 (이슈 #261).
     */
    private Map<String, Double> computeThresholds(double riskPrior, boolean forceSensitive) {
        if (riskPrior > 0.4 || forceSensitive) {
            return SENSITIVE_THRESHOLDS;
        }
        return DEFAULT_THRESHOLDS;
    }

    // ── 내부 DTO ──────────────────────────────────────────────────

    /**
     * 위기 이력 조회 결과 (이슈 #261).
     *
     * @param resolved 조회에 성공했는지. {@code false} 면 {@code maxSeverity} 는 판정값이 아니다.
     */
    private record CrisisHistory(int maxSeverity, boolean resolved) {
        static CrisisHistory resolved(int maxSeverity) {
            return new CrisisHistory(maxSeverity, true);
        }

        static CrisisHistory unresolved() {
            return new CrisisHistory(0, false);
        }
    }

    private record BeliefSummary(int negativeCount, String copingStyle, boolean hasData) {}
    private record PatternSummary(List<String> topDistortionCodes, List<String> triggerKinds) {}
    private record OutcomeSummary(List<String> effectiveKinds, List<String> ineffectiveKinds) {}
    private record SessionMeta(int totalSessions, int daysSinceFirst) {}
}
