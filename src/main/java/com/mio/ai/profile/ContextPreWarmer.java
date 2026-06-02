package com.mio.ai.profile;

import com.mio.ai.memory.composer.ContextComposer;
import com.mio.ai.memory.retrieval.FusionRanker;
import com.mio.ai.memory.retrieval.RetrievalPlan;
import com.mio.ai.memory.retrieval.RetrievedItem;
import com.mio.ai.memory.retrieval.StructuredRetriever;
import com.mio.ai.memory.retrieval.VectorRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * POST /v1/sessions 직후 비동기 context + safety profile 사전 빌드 (§12.4.1).
 * 사용자 typing 5~30초 동안 Redis에 pre-warming.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContextPreWarmer {

    private static final String CONTEXT_CACHE_KEY = "session:%s:context_cache";
    private static final Duration CONTEXT_TTL = Duration.ofMinutes(5);

    private final StructuredRetriever structuredRetriever;
    private final VectorRetriever vectorRetriever;
    private final FusionRanker fusionRanker;
    private final ContextComposer contextComposer;
    private final SafetyProfileBuilder safetyProfileBuilder;
    private final StringRedisTemplate redisTemplate;

    @Async
    public void preWarm(UUID sessionId, UUID userId) {
        log.debug("ContextPreWarmer: pre-warming sessionId={}", sessionId);
        try {
            // 1. SafetyProfile 빌드 (캐시 저장은 SafetyProfileBuilder 내부에서)
            SafetyProfile profile = safetyProfileBuilder.buildAndCache(sessionId.toString(), userId.toString());

            // 2. 기본 컨텍스트: profile + rhythm + recent risk
            RetrievalPlan plan = RetrievalPlan.clearLow();
            List<List<RetrievedItem>> results = executeParallel(userId, plan, null);
            List<RetrievedItem> ranked = fusionRanker.rank(results, plan.sensitivityCap(), plan.maxK() * 3);
            String context = contextComposer.compose(ranked, plan.sensitivityCap(), false);

            // 3. Redis 캐싱
            if (context != null && !context.isBlank()) {
                redisTemplate.opsForValue().set(
                        CONTEXT_CACHE_KEY.formatted(sessionId), context, CONTEXT_TTL
                );
                log.debug("ContextPreWarmer: cached context for sessionId={} length={}",
                        sessionId, context.length());
            }
        } catch (Exception e) {
            log.warn("ContextPreWarmer failed for sessionId={}", sessionId, e);
        }
    }

    public String getCachedContext(UUID sessionId) {
        try {
            return redisTemplate.opsForValue().get(CONTEXT_CACHE_KEY.formatted(sessionId));
        } catch (Exception e) {
            log.warn("ContextPreWarmer.getCachedContext failed", e);
            return null;
        }
    }

    private List<List<RetrievedItem>> executeParallel(UUID userId, RetrievalPlan plan, String queryText) {
        List<List<RetrievedItem>> results = new ArrayList<>();
        int k = plan.maxK();

        for (var source : plan.sources()) {
            switch (source) {
                case VECTOR_EPISODE  -> results.add(vectorRetriever.retrieveEpisodes(userId, null, k));
                case VECTOR_BELIEF   -> results.add(vectorRetriever.retrieveBeliefs(userId, null, k));
                case SQL_PROFILE     -> results.add(structuredRetriever.retrieveProfile(userId));
                case SQL_RHYTHM      -> results.add(structuredRetriever.retrieveRhythm(userId));
                case SQL_RECENT_RISK -> results.add(structuredRetriever.retrieveRecentRisk(userId));
                case SQL_TODO_HISTORY -> results.add(structuredRetriever.retrieveTodoHistory(userId));
                case GRAPH_TRIGGER   -> results.add(structuredRetriever.retrieveTriggers(userId, List.of()));
                case GRAPH_INTERVENTION_FIT -> results.add(structuredRetriever.retrieveInterventionFit(userId));
                default -> {}
            }
        }
        return results;
    }
}
