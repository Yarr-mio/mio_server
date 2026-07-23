package com.mio.ai.profile;

import com.mio.ai.AiCacheKeys;
import com.mio.ai.llm.EmbeddingClient;
import com.mio.ai.memory.composer.ContextComposer;
import com.mio.ai.memory.retrieval.FusionRanker;
import com.mio.ai.memory.retrieval.LexicalRetriever;
import com.mio.ai.memory.retrieval.MemoryRetrievalPlanner;
import com.mio.ai.memory.retrieval.RetrievalPlan;
import com.mio.ai.memory.retrieval.RetrievedItem;
import com.mio.ai.memory.retrieval.StructuredRetriever;
import com.mio.ai.memory.retrieval.VectorRetriever;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.safety.CombinedSignal;
import com.mio.session.repository.SessionCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private static final Duration CHECKPOINT_TTL = Duration.ofHours(2);
    private static final long MAX_EMBEDDING_WAIT_MS = 250;

    private final StructuredRetriever structuredRetriever;
    private final VectorRetriever vectorRetriever;
    private final LexicalRetriever lexicalRetriever;
    private final EmbeddingClient embeddingClient;
    private final FusionRanker fusionRanker;
    private final ContextComposer contextComposer;
    private final MemoryRetrievalPlanner memoryRetrievalPlanner;
    private final SafetyProfileBuilder safetyProfileBuilder;
    private final SessionCheckpointRepository checkpointRepository;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final WorkingMemory workingMemory;

    private final Executor retrievalPool = Executors.newVirtualThreadPerTaskExecutor();

    @Async
    public void preWarm(UUID sessionId, UUID userId) {
        log.debug("ContextPreWarmer: pre-warming sessionId={}", sessionId);
        try {
            // 1. SafetyProfile 빌드 + 캐싱
            safetyProfileBuilder.buildAndCache(sessionId.toString(), userId.toString());

            // 2. 기본 컨텍스트만 캐시한다. 현재 발화 기반 검색은 각 대화 턴에서 수행한다.
            RetrievalPlan plan = RetrievalPlan.staticBase();
            List<List<RetrievedItem>> results = retrieveParallel(sessionId, userId, plan, null, null);
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

            // 4. 최신 체크포인트 요약 Redis 캐싱
            checkpointRepository.findTopBySession_IdOrderByCheckpointSeqDesc(sessionId)
                    .ifPresent(cp -> {
                        String summary = cp.getSummaryText();
                        if (summary != null && !summary.isBlank()) {
                            redisTemplate.opsForValue().set(
                                    AiCacheKeys.CHECKPOINT_CACHE_KEY.formatted(sessionId), summary, CHECKPOINT_TTL
                            );
                            log.debug("ContextPreWarmer: cached checkpoint seq={} sessionId={}",
                                    cp.getCheckpointSeq(), sessionId);
                        }
                    });
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

    public String getCachedCheckpoint(UUID sessionId) {
        try {
            return redisTemplate.opsForValue().get(AiCacheKeys.CHECKPOINT_CACHE_KEY.formatted(sessionId));
        } catch (Exception e) {
            log.warn("ContextPreWarmer.getCachedCheckpoint failed", e);
            return null;
        }
    }

    /**
     * cache MISS 시 동기 fallback — 실시간 risk tier 기반 동적 검색 (§12.4 MISS → ~50ms).
     */
    public String buildContextSync(UUID sessionId, UUID userId, CombinedSignal combined,
                                   SafetyProfile profile, String queryText) {
        try {
            boolean hasHistory = checkHasHistory(userId);
            RetrievalPlan plan = memoryRetrievalPlanner.plan(combined, profile, userId, hasHistory);
            float[] queryEmbedding = embedIfNeeded(plan, queryText);
            List<List<RetrievedItem>> results = retrieveParallel(sessionId, userId, plan, queryEmbedding, queryText);
            List<RetrievedItem> ranked = fusionRanker.rank(results, plan.sensitivityCap(), plan.maxK() * 3);
            boolean highRisk = combined.hardCrisis() || combined.riskCandidate();
            return contextComposer.compose(ranked, plan.sensitivityCap(), highRisk);
        } catch (Exception e) {
            log.warn("ContextPreWarmer.buildContextSync failed for sessionId={}", sessionId, e);
            return null;
        }
    }

    // ── 실제 병렬 retrieval (CompletableFuture) ────────────────────

    private float[] embedIfNeeded(RetrievalPlan plan, String queryText) {
        if (!plan.sources().contains(com.mio.ai.memory.retrieval.RetrievalSource.VECTOR_EPISODE)
                || queryText == null || queryText.isBlank()) {
            return null;
        }
        CompletableFuture<float[]> embeddingFuture = CompletableFuture.supplyAsync(
                () -> embeddingClient.embed(queryText), retrievalPool);
        try {
            long timeoutMs = Math.min(plan.budgetMs(), MAX_EMBEDDING_WAIT_MS);
            return embeddingFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            embeddingFuture.cancel(true);
            log.warn("ContextPreWarmer: embedding interrupted; continuing without vector retrieval");
        } catch (Exception e) {
            embeddingFuture.cancel(true);
            log.warn("ContextPreWarmer: embedding failed; continuing without vector retrieval", e);
        }
        return null;
    }

    private List<List<RetrievedItem>> retrieveParallel(UUID sessionId, UUID userId, RetrievalPlan plan,
                                                         float[] queryEmbedding, String queryText) {
        int k = plan.maxK();
        List<CompletableFuture<List<RetrievedItem>>> futures = new ArrayList<>();

        for (var source : plan.sources()) {
            CompletableFuture<List<RetrievedItem>> future = switch (source) {
                case VECTOR_EPISODE      -> queryEmbedding == null
                        ? CompletableFuture.completedFuture(List.of())
                        : CompletableFuture.supplyAsync(
                                () -> vectorRetriever.retrieveEpisodes(userId, queryEmbedding, k), retrievalPool);
                case LEXICAL_EPISODE     -> CompletableFuture.supplyAsync(
                        () -> lexicalRetriever.retrieveByKeywords(userId, queryText, k), retrievalPool);
                case VECTOR_BELIEF       -> CompletableFuture.supplyAsync(
                        () -> vectorRetriever.retrieveBeliefs(userId, null, k), retrievalPool);
                case SQL_PROFILE         -> CompletableFuture.supplyAsync(
                        () -> structuredRetriever.retrieveProfile(userId), retrievalPool);
                case SQL_RHYTHM          -> CompletableFuture.supplyAsync(
                        () -> structuredRetriever.retrieveRhythm(userId), retrievalPool);
                case SQL_RECENT_RISK     -> CompletableFuture.supplyAsync(
                        () -> structuredRetriever.retrieveRecentRisk(userId), retrievalPool);
                case SQL_TODO_HISTORY    -> CompletableFuture.supplyAsync(
                        () -> structuredRetriever.retrieveTodoHistory(userId), retrievalPool);
                case GRAPH_TRIGGER       -> CompletableFuture.supplyAsync(() -> {
                    try {
                        List<String> triggers = new ArrayList<>(
                                workingMemory.getSessionDelta(sessionId).currentSessionTriggers());
                        return structuredRetriever.retrieveTriggers(userId, triggers);
                    } catch (Exception e) {
                        log.warn("ContextPreWarmer: GRAPH_TRIGGER triggers fetch failed for sessionId={}", sessionId, e);
                        return Collections.<RetrievedItem>emptyList();
                    }
                }, retrievalPool);
                case GRAPH_INTERVENTION_FIT -> CompletableFuture.supplyAsync(
                        () -> structuredRetriever.retrieveInterventionFit(userId), retrievalPool);
                case GRAPH_BELIEF_NEIGH  -> CompletableFuture.supplyAsync(
                        () -> structuredRetriever.retrieveBeliefNeighbors(userId), retrievalPool);
                default                  -> CompletableFuture.completedFuture(List.of());
            };
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(list -> !list.isEmpty())
                .toList();
    }

    private boolean checkHasHistory(UUID userId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM session_summaries WHERE user_id = ? LIMIT 1",
                    Integer.class, userId
            );
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
