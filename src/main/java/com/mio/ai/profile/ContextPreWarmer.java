package com.mio.ai.profile;

import com.mio.ai.AiCacheKeys;
import com.mio.ai.llm.EmbeddingClient;
import com.mio.ai.memory.composer.ContextComposer;
import com.mio.ai.memory.ontology.OntologyRelationExpander;
import com.mio.ai.memory.retrieval.FusionRanker;
import com.mio.ai.memory.retrieval.LexicalRetriever;
import com.mio.ai.memory.retrieval.MemoryContextResult;
import com.mio.ai.memory.retrieval.MemoryRetrievalPlanner;
import com.mio.ai.memory.retrieval.RetrievalPlan;
import com.mio.ai.memory.retrieval.RetrievalSource;
import com.mio.ai.memory.retrieval.RetrievedItem;
import com.mio.ai.memory.retrieval.StructuredRetriever;
import com.mio.ai.memory.retrieval.VectorRetriever;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.safety.CombinedSignal;
import com.mio.session.repository.SessionCheckpointRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * 검색 소스별 성공·실패 카운터 (이슈 #364).
     *
     * <p>실패율을 알 수 없으면 "기억을 못 쓰는 턴이 늘고 있다" 를 탐지할 방법이 없다.
     * 소스별로 나누는 이유는 pgvector 장애와 일반 SQL 장애가 다른 대응을 요구하기 때문이다.
     */
    private static final String RETRIEVAL_METRIC = "mio.retrieval.outcome";

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
    private final OntologyRelationExpander ontologyRelationExpander;
    private final MeterRegistry meterRegistry;

    private final Executor retrievalPool = Executors.newVirtualThreadPerTaskExecutor();

    @Async
    public void preWarm(UUID sessionId, UUID userId) {
        log.debug("ContextPreWarmer: pre-warming sessionId={}", sessionId);
        try {
            // 1. SafetyProfile 빌드 + 캐싱
            safetyProfileBuilder.buildAndCache(sessionId.toString(), userId.toString());

            // 2. 기본 컨텍스트만 캐시한다. 현재 발화 기반 검색은 각 대화 턴에서 수행한다.
            RetrievalPlan plan = RetrievalPlan.staticBase();
            List<List<RetrievedItem>> results = retrieveParallel(
                    sessionId, userId, plan, null, null, Set.of(), ConcurrentHashMap.newKeySet());
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
    public MemoryContextResult buildContextSync(UUID sessionId, UUID userId, CombinedSignal combined,
                                                SafetyProfile profile, String queryText) {
        return buildContextSync(sessionId, userId, combined, profile, queryText, null);
    }

    /**
     * 부분 실패를 허용한다 (이슈 #364).
     *
     * <p>소스 하나가 죽었다고 턴 전체를 기억 없이 보내는 것보다, 남은 소스로 컨텍스트를
     * 만들고 <b>무엇이 죽었는지 기록</b>하는 편이 낫다. 반대로 조립 자체가 실패하면
     * {@code null} 이 아니라 {@code FAILED} 상태를 돌려준다 — 호출부가 "기억 없음" 과
     * 구별할 수 있어야 한다(§10.1).
     */
    public MemoryContextResult buildContextSync(UUID sessionId, UUID userId, CombinedSignal combined,
                                                SafetyProfile profile, String queryText,
                                                String currentDistortionCode) {
        Set<RetrievalSource> failedSources = ConcurrentHashMap.newKeySet();
        try {
            boolean hasHistory = checkHasHistory(userId);
            RetrievalPlan plan = memoryRetrievalPlanner.plan(combined, profile, userId, hasHistory);
            float[] queryEmbedding = embedIfNeeded(plan, queryText, failedSources);
            Set<String> relatedDistortionCodes = plan.sources().contains(RetrievalSource.GRAPH_DISTORTION)
                    ? ontologyRelationExpander.expandCooccurringCodes(currentDistortionCode)
                    : Set.of();
            List<List<RetrievedItem>> results = retrieveParallel(
                    sessionId, userId, plan, queryEmbedding, queryText, relatedDistortionCodes, failedSources);
            List<RetrievedItem> ranked = fusionRanker.rank(results, plan.sensitivityCap(), plan.maxK() * 3);
            boolean highRisk = combined.hardCrisis() || combined.riskCandidate();
            String text = contextComposer.compose(ranked, plan.sensitivityCap(), highRisk);
            return MemoryContextResult.partial(text, failedSources);
        } catch (Exception e) {
            log.warn("ContextPreWarmer.buildContextSync failed for sessionId={}", sessionId, e);
            return MemoryContextResult.failed();
        }
    }

    // ── 실제 병렬 retrieval (CompletableFuture) ────────────────────

    /**
     * 임베딩 실패는 {@code VECTOR_EPISODE} 소스 실패로 기록한다 (이슈 #364).
     *
     * <p>이전에는 조용히 {@code null} 을 반환해 벡터 검색을 건너뛰었다. 그러면 임베딩
     * 타임아웃이 잦아져도 "벡터 검색 결과가 원래 없다" 와 구별되지 않는다.
     */
    private float[] embedIfNeeded(RetrievalPlan plan, String queryText,
                                  Set<RetrievalSource> failedSources) {
        if (!plan.sources().contains(RetrievalSource.VECTOR_EPISODE)
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
        markFailed(failedSources, RetrievalSource.VECTOR_EPISODE);
        return null;
    }

    private void markFailed(Set<RetrievalSource> failedSources, RetrievalSource source) {
        failedSources.add(source);
        meterRegistry.counter(RETRIEVAL_METRIC, "source", source.name(), "outcome", "failed").increment();
    }

    /**
     * 소스별로 결과를 모으되, 죽은 소스는 {@code failedSources} 에 남긴다 (이슈 #364).
     *
     * <p>실패 처리를 각 검색기가 아니라 여기서만 하는 이유는, 어떤 소스가 죽었는지는
     * 소스를 아는 이 지점에서만 알 수 있기 때문이다. 검색기 안에서 {@code emptyList()} 로
     * 바꿔 버리면 그 정보가 반환값에서 사라진다.
     */
    private List<List<RetrievedItem>> retrieveParallel(UUID sessionId, UUID userId, RetrievalPlan plan,
                                                         float[] queryEmbedding, String queryText,
                                                         Set<String> relatedDistortionCodes,
                                                         Set<RetrievalSource> failedSources) {
        int k = plan.maxK();
        List<CompletableFuture<List<RetrievedItem>>> futures = new ArrayList<>();

        for (var source : plan.sources()) {
            CompletableFuture<List<RetrievedItem>> raw = switch (source) {
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
                    List<String> triggers = new ArrayList<>(
                            workingMemory.getSessionDelta(sessionId).currentSessionTriggers());
                    return structuredRetriever.retrieveTriggers(userId, triggers);
                }, retrievalPool);
                case GRAPH_DISTORTION    -> CompletableFuture.supplyAsync(
                        () -> structuredRetriever.retrieveRelatedDistortionEpisodes(userId, relatedDistortionCodes),
                        retrievalPool);
                case GRAPH_INTERVENTION_FIT -> CompletableFuture.supplyAsync(
                        () -> structuredRetriever.retrieveInterventionFit(userId), retrievalPool);
                case GRAPH_BELIEF_NEIGH  -> CompletableFuture.supplyAsync(
                        () -> structuredRetriever.retrieveBeliefNeighbors(
                                userId, workingMemory.getSessionDelta(sessionId).activatedBeliefIds()),
                        retrievalPool);
                default                  -> CompletableFuture.completedFuture(List.<RetrievedItem>of());
            };

            futures.add(raw.handle((items, error) -> {
                if (error != null) {
                    log.warn("ContextPreWarmer: retrieval source {} failed for sessionId={}",
                            source, sessionId, error);
                    markFailed(failedSources, source);
                    return List.<RetrievedItem>of();
                }
                meterRegistry.counter(RETRIEVAL_METRIC, "source", source.name(), "outcome", "ok").increment();
                return items;
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(list -> !list.isEmpty())
                .toList();
    }

    /**
     * 이력 유무 확인. 실패해도 {@code false} 로 삼키지 않는다 (이슈 #364).
     *
     * <p>이 값이 틀리면 검색 계획 자체가 틀린다. "이력 없음" 으로 삼키면 기억이 있는
     * 사용자에게 신규 사용자용 계획이 적용되고, 그 사실이 아무 데도 남지 않는다.
     * 예외를 올려 {@code buildContextSync} 가 {@code FAILED} 로 판정하게 한다.
     */
    private boolean checkHasHistory(UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_summaries WHERE user_id = ? LIMIT 1",
                Integer.class, userId
        );
        return count != null && count > 0;
    }
}
