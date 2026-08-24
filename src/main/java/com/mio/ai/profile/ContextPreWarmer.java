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
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
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
import java.util.concurrent.TimeoutException;

/**
 * POST /v1/sessions 직후 비동기 context + safety profile 사전 빌드 (§12.4.1).
 * 사용자 typing 5~30초 동안 Redis에 pre-warming.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContextPreWarmer {

    private static final String CONTEXT_CACHE_KEY = "session:%s:context_cache";

    /**
     * 위험 턴용 캐시 변형 (이슈 #522).
     *
     * <p>사전 웜업은 사용자의 <b>다음</b> 발화를 모르므로 그 턴의 위험도를 알 수 없다. 그래서
     * 지금까지 {@code highRisk=false} 하나만 구웠고, 라이브 검색이 비어 폴백이 채택되면
     * {@link ContextComposer} 의 highRisk 필터를 <b>거치지 않은</b> 에피소드·신념 기억이
     * 위기 턴 프롬프트에 들어갔다. 조립이 끝난 문자열이라 사후 재필터도 불가능하다.
     *
     * <p>모르는 값을 굽는 시점에 정할 수는 없으니 <b>두 변형을 모두 굽는다.</b> 채택 시점에
     * 실제 위험도로 고른다. 같은 랭킹 결과에 조립만 한 번 더 하는 것이고 조립은 2,000자 이내
     * 문자열 빌드라 비용이 사실상 없다.
     */
    private static final String CONTEXT_CACHE_KEY_HIGH_RISK = "session:%s:context_cache:high_risk";
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

    /** 일반 실패. 외부 장애·쿼리 오류 등 "고쳐야 하는" 실패다. */
    private static final String OUTCOME_FAILED = "failed";

    /**
     * 대기 상한 초과 (이슈 #495).
     *
     * <p>{@link #OUTCOME_FAILED} 와 나누는 이유는 <b>대응이 다르기 때문</b>이다. 일반 실패는
     * 외부 장애를 의심해야 하지만, 타임아웃은 상한이 실제 왕복 시간보다 짧다는 <b>튜닝 신호</b>다.
     * 한 값으로 묶여 있으면 지표만 보고 둘을 구별할 수 없다 — 프로덕션에서 이 경로가
     * 시도 57턴 중 54턴 실패로 관측됐는데, 그것이 장애인지 설정 문제인지 알 수 없었다.
     */
    private static final String OUTCOME_TIMEOUT = "timeout";

    /**
     * 임베딩 왕복 시간 (이슈 #495).
     *
     * <p><b>호출자가 대기를 포기했는지와 무관하게</b> 실제 완료 시점에 기록한다.
     * 이 값이 없으면 {@link #MAX_EMBEDDING_WAIT_MS} 를 얼마로 올려야 하는지 판단할 근거가 없다 —
     * 타임아웃 카운터만으로는 "250ms 를 넘었다" 는 사실만 알 뿐 260ms 인지 3초인지 모른다.
     */
    private static final String EMBEDDING_WAIT_METRIC = "mio.retrieval.embedding.wait";

    /**
     * 이력 확인 쿼리의 메트릭 라벨.
     *
     * <p>{@code RetrievalSource} 값이 아니다 — 검색 소스가 아니라 계획을 세우는 데 쓰는
     * 사전 조회이므로 enum 에 넣지 않는다. 그 enum 은 FusionRanker·planner 의 switch 가
     * 함께 보는 값이라, 소스가 아닌 것을 끼워 넣으면 그쪽까지 흔든다.
     */
    private static final String HISTORY_PROBE = "HISTORY_PROBE";

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

    /** {@link #initEmbeddingWaitTimers} 에서 기동 시 1회만 만든다 (이슈 #495). */
    private Timer embeddingWaitSuccessTimer;
    private Timer embeddingWaitFailureTimer;

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
            String highRiskContext = contextComposer.compose(ranked, plan.sensitivityCap(), true);

            // 3. Redis 캐싱 — 위험도별 두 변형. 채택 시점에 고른다 (이슈 #522).
            cacheVariant(CONTEXT_CACHE_KEY_HIGH_RISK.formatted(sessionId), highRiskContext, sessionId);
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

    /**
     * 위험도에 맞는 캐시 변형을 읽는다 (이슈 #522).
     *
     * <p>호출자는 {@link CombinedSignal} 만 넘기고 위험도를 스스로 해석하지 않는다. 굽는 쪽과
     * 채택하는 쪽이 각자 계산하면 정의가 갈라질 수 있고, 갈라지는 순간 이 결함이 그대로
     * 돌아온다. 판정은 {@link #isHighRisk(CombinedSignal)} 하나뿐이다.
     *
     * <p><b>변형이 없으면 다른 변형으로 대체하지 않는다.</b> TTL 이 지났거나 이 수정 배포 전에
     * 구워진 캐시가 남아 있을 수 있다. 그때 일반 변형을 대신 쓰면 필터 미적용 내용이 위기 턴에
     * 들어가는 원래 결함이 된다 — 기억 없이 가는 편이 필터 안 된 기억보다 안전하다.
     */
    public String getCachedContext(UUID sessionId, CombinedSignal combined) {
        try {
            return redisTemplate.opsForValue().get(contextCacheKey(sessionId, combined));
        } catch (Exception e) {
            log.warn("ContextPreWarmer.getCachedContext failed", e);
            return null;
        }
    }

    /**
     * 채택된 캐시의 나이(ms). 읽을 수 없으면 {@code null} (이슈 #522).
     *
     * <p>폴백이 얼마나 낡은 문맥을 주입했는지 알 수 없으면 이 경로의 품질을 사후에 판정할 수
     * 없다. 남은 TTL 로 역산하므로 <b>값 형식을 바꾸지 않는다</b> — 굽는 쪽에 타임스탬프를
     * 심으면 기존 캐시와 호환이 깨진다.
     *
     * <p>못 읽으면 0 이 아니라 {@code null} 이다. 0 은 "방금 구웠다" 는 뜻이 되어 관측을
     * 거짓으로 낙관하게 만든다.
     */
    public Long getCachedContextAgeMs(UUID sessionId, CombinedSignal combined) {
        try {
            Long remaining = redisTemplate.getExpire(
                    contextCacheKey(sessionId, combined), TimeUnit.MILLISECONDS);
            if (remaining == null || remaining < 0) {
                return null;
            }
            return Math.max(0L, CONTEXT_TTL.toMillis() - remaining);
        } catch (Exception e) {
            log.warn("ContextPreWarmer.getCachedContextAgeMs failed", e);
            return null;
        }
    }

    /**
     * 고위험 턴 판정. <b>이 프로젝트에서 이 정의는 여기 한 곳뿐이어야 한다</b> —
     * 캐시를 굽는 쪽과 채택하는 쪽이 같은 판정을 써야 이슈 #522 가 재발하지 않는다.
     */
    private static boolean isHighRisk(CombinedSignal combined) {
        return combined != null && (combined.hardCrisis() || combined.riskCandidate());
    }

    private static String contextCacheKey(UUID sessionId, CombinedSignal combined) {
        return isHighRisk(combined)
                ? CONTEXT_CACHE_KEY_HIGH_RISK.formatted(sessionId)
                : CONTEXT_CACHE_KEY.formatted(sessionId);
    }

    private void cacheVariant(String key, String value, UUID sessionId) {
        if (value == null || value.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(key, value, CONTEXT_TTL);
        log.debug("ContextPreWarmer: cached context variant key={} sessionId={} length={}",
                key, sessionId, value.length());
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
        HistoryProbe historyProbe = probeHistory(userId);
        try {
            RetrievalPlan plan = memoryRetrievalPlanner.plan(
                    combined, profile, userId, historyProbe.hasHistory());
            float[] queryEmbedding = embedIfNeeded(
                    plan, queryText, userId, sessionId, failedSources);
            Set<String> relatedDistortionCodes =
                    expandRelatedCodes(plan, currentDistortionCode, failedSources);
            List<List<RetrievedItem>> results = retrieveParallel(
                    sessionId, userId, plan, queryEmbedding, queryText, relatedDistortionCodes, failedSources);
            List<RetrievedItem> ranked = fusionRanker.rank(results, plan.sensitivityCap(), plan.maxK() * 3);
            boolean highRisk = isHighRisk(combined);
            String text = contextComposer.compose(ranked, plan.sensitivityCap(), highRisk);
            return MemoryContextResult.partial(text, failedSources, historyProbe.degraded());
        } catch (Exception e) {
            log.warn("ContextPreWarmer.buildContextSync failed for sessionId={}", sessionId, e);
            return MemoryContextResult.failed();
        }
    }

    /** 이력 확인 결과. 확인에 실패했는지를 값으로 들고 다닌다. */
    private record HistoryProbe(boolean hasHistory, boolean degraded) {}

    /**
     * 이력 유무를 확인하되, 실패해도 검색 전체를 포기하지 않는다.
     *
     * <p>이 값이 틀리면 검색 <b>계획</b>이 틀린다. 그렇다고 계획 하나 때문에 멀쩡한 소스까지
     * 버리면, 일시적인 {@code COUNT} 실패가 그 턴의 기억을 통째로 없앤다 — 관측 가능성을
     * 얻으려다 가용성을 잃는 교환이다.
     *
     * <p>실패 시 <b>이력이 있다고 가정</b>한다. 소스가 더 많은 쪽이고, 실제로 이력이 없으면
     * 그 쿼리들이 빈 결과를 돌려줄 뿐이다. 대신 계획이 추측이었다는 사실을
     * {@code planDegraded} 로 남긴다.
     */
    private HistoryProbe probeHistory(UUID userId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM session_summaries WHERE user_id = ? LIMIT 1",
                    Integer.class, userId
            );
            return new HistoryProbe(count != null && count > 0, false);
        } catch (Exception e) {
            log.warn("ContextPreWarmer: history probe failed for userId={}; assuming history exists", userId, e);
            meterRegistry.counter(RETRIEVAL_METRIC, "source", HISTORY_PROBE, "outcome", OUTCOME_FAILED).increment();
            return new HistoryProbe(true, true);
        }
    }

    /**
     * 온톨로지 확장 실패를 {@code GRAPH_DISTORTION} 실패로 기록한다 (이슈 #364).
     *
     * <p>확장 결과가 비면 그 소스는 아무것도 조회하지 않는다. 그러니 확장이 실패한 것과
     * "동반 왜곡이 원래 없는 것" 이 구별되지 않으면, 검색기에서 고친 것과 같은 결함이
     * 한 단계 앞에 남는다.
     */
    private Set<String> expandRelatedCodes(RetrievalPlan plan, String currentDistortionCode,
                                           Set<RetrievalSource> failedSources) {
        if (!plan.sources().contains(RetrievalSource.GRAPH_DISTORTION)) {
            return Set.of();
        }
        try {
            return ontologyRelationExpander.expandCooccurringCodes(currentDistortionCode);
        } catch (Exception e) {
            log.warn("ContextPreWarmer: ontology relation expansion failed", e);
            markFailed(failedSources, RetrievalSource.GRAPH_DISTORTION);
            return Set.of();
        }
    }

    // ── 실제 병렬 retrieval (CompletableFuture) ────────────────────

    /**
     * 임베딩 실패는 {@code VECTOR_EPISODE} 소스 실패로 기록한다 (이슈 #364).
     *
     * <p>이전에는 조용히 {@code null} 을 반환해 벡터 검색을 건너뛰었다. 그러면 임베딩
     * 타임아웃이 잦아져도 "벡터 검색 결과가 원래 없다" 와 구별되지 않는다.
     *
     * <p>단, <b>대기 상한 초과는 {@link #OUTCOME_TIMEOUT} 으로 따로 센다</b> (이슈 #495).
     * 외부 장애와 상한 부족은 대응이 다른데, 한 값으로 묶여 있으면 지표로 구별할 수 없다.
     */
    private float[] embedIfNeeded(RetrievalPlan plan, String queryText, UUID userId, UUID sessionId,
                                  Set<RetrievalSource> failedSources) {
        if (!plan.sources().contains(RetrievalSource.VECTOR_EPISODE)
                || queryText == null || queryText.isBlank()) {
            return null;
        }
        CompletableFuture<float[]> embeddingFuture = CompletableFuture.supplyAsync(
                () -> embedTimed(queryText, userId, sessionId), retrievalPool);
        long timeoutMs = Math.min(plan.budgetMs(), MAX_EMBEDDING_WAIT_MS);
        try {
            return embeddingFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 상한 초과는 장애가 아니라 튜닝 신호다. 아래 cancel 은 이미 시작된 작업을 멈추지
            // 못하므로(CompletableFuture.cancel 은 실행 중인 스레드를 인터럽트하지 않는다),
            // embedTimed 의 타이머는 그대로 완료 시점에 기록된다 — 상한을 얼마로 올려야 하는지는
            // 그 값으로만 알 수 있다.
            embeddingFuture.cancel(true);
            log.warn("ContextPreWarmer: embedding wait exceeded {}ms; continuing without vector retrieval",
                    timeoutMs);
            markRetrievalOutcome(failedSources, RetrievalSource.VECTOR_EPISODE, OUTCOME_TIMEOUT);
            return null;
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

    /**
     * 임베딩을 부르고 왕복 시간을 남긴다.
     *
     * <p>계측을 호출자 쪽이 아니라 <b>비동기 작업 안에</b> 두는 것이 핵심이다. 호출자는 상한을
     * 넘기면 결과를 버리는데, 버려진 호출이야말로 "얼마나 오래 걸렸는가" 를 알려주는 표본이다.
     *
     * <p>성공·실패를 태그로 나눈다. 상한을 정하는 근거는 <b>성공한 호출</b>의 분포이고,
     * 빠르게 떨어지는 4xx 같은 실패가 섞이면 그 p95 가 실제보다 낮게 나온다.
     */
    private float[] embedTimed(String queryText, UUID userId, UUID sessionId) {
        long startedAt = System.nanoTime();
        boolean succeeded = false;
        try {
            float[] embedding = embeddingClient.embed(queryText, "RETRIEVAL_QUERY_EMBEDDING", userId, sessionId);
            succeeded = true;
            return embedding;
        } finally {
            recordEmbeddingWait(succeeded, System.nanoTime() - startedAt);
        }
    }

    /**
     * 왕복 시간 기록. <b>실패해도 호출 결과를 덮지 않는다.</b>
     *
     * <p>{@code finally} 안에서 예외가 나가면 자바 의미론상 그 예외가 원래 반환값 또는 원래
     * 예외를 통째로 대체한다. 그러면 <b>성공한 임베딩이 실패로 오분류되어 버려지고</b>,
     * 실패 경로에서는 진짜 원인이 관측 예외로 덮인다 — 이 클래스가 없애려는 문제를
     * 관측 코드가 다시 만드는 셈이다. 그래서 여기서 끊는다.
     */
    private void recordEmbeddingWait(boolean succeeded, long elapsedNanos) {
        try {
            Timer timer = succeeded ? embeddingWaitSuccessTimer : embeddingWaitFailureTimer;
            timer.record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.warn("ContextPreWarmer: failed to record embedding wait metric", e);
        }
    }

    /**
     * 왕복 시간 타이머를 기동 시 한 번만 만든다.
     *
     * <p>{@code Timer.builder(...).register(...)} 는 같은 이름·태그면 기존 미터를 돌려주지만
     * 그 확인을 위해 레지스트리 락을 잡는다. 턴 크리티컬 패스에서 매번 부르면 동시 턴이
     * 전부 공유하는 동기화 지점이 된다 — 캐리어 스레드가 2개(2 vCPU)인 환경에서는 특히 아깝다.
     */
    @PostConstruct
    void initEmbeddingWaitTimers() {
        embeddingWaitSuccessTimer = embeddingWaitTimer("success");
        embeddingWaitFailureTimer = embeddingWaitTimer("failure");
    }

    private Timer embeddingWaitTimer(String outcome) {
        return Timer.builder(EMBEDDING_WAIT_METRIC)
                .description("검색용 임베딩 왕복 시간. 호출자의 대기 포기 여부와 무관하게 기록한다.")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofSeconds(10))
                // 자동 백분위 버킷이 이미 10~20% 간격으로 깔리므로 SLO 는 현재 상한 하나만 둔다.
                // 나머지 라운드 숫자는 자동 버킷과 겹쳐 시계열만 늘리고 해상도 이득이 없다.
                .serviceLevelObjectives(Duration.ofMillis(MAX_EMBEDDING_WAIT_MS))
                .register(meterRegistry);
    }

    private void markFailed(Set<RetrievalSource> failedSources, RetrievalSource source) {
        markRetrievalOutcome(failedSources, source, OUTCOME_FAILED);
    }

    private void markRetrievalOutcome(Set<RetrievalSource> failedSources, RetrievalSource source, String outcome) {
        failedSources.add(source);
        meterRegistry.counter(RETRIEVAL_METRIC, "source", source.name(), "outcome", outcome).increment();
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
                // null 은 예외를 던지지 않은 실패다. 아래 filter 에서 NPE 로 터지면 소스 하나가
                // 턴 전체를 죽이고, 그것도 실패로 기록되지 않는다 — 이 이슈가 없애려는 형태다.
                if (items == null) {
                    log.warn("ContextPreWarmer: retrieval source {} returned null for sessionId={}",
                            source, sessionId);
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

}
