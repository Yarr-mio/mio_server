package com.mio.ai.profile;

import com.mio.ai.llm.EmbeddingClient;
import com.mio.ai.memory.composer.ContextComposer;
import com.mio.ai.memory.retrieval.FusionRanker;
import com.mio.ai.memory.retrieval.LexicalRetriever;
import com.mio.ai.memory.retrieval.MemoryRetrievalPlanner;
import com.mio.ai.memory.retrieval.RetrievalPlan;
import com.mio.ai.memory.retrieval.RetrievalSource;
import com.mio.ai.memory.retrieval.RetrievedItem;
import com.mio.ai.memory.retrieval.StructuredRetriever;
import com.mio.ai.memory.retrieval.VectorRetriever;
import com.mio.ai.memory.ontology.OntologyRelationExpander;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.memory.retrieval.MemoryContextResult;
import com.mio.ai.memory.retrieval.MemoryContextStatus;
import com.mio.session.repository.SessionCheckpointRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextPreWarmerTest {

    private final StructuredRetriever structuredRetriever = mock(StructuredRetriever.class);
    private final VectorRetriever vectorRetriever = mock(VectorRetriever.class);
    private final LexicalRetriever lexicalRetriever = mock(LexicalRetriever.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final FusionRanker fusionRanker = mock(FusionRanker.class);
    private final ContextComposer contextComposer = mock(ContextComposer.class);
    private final MemoryRetrievalPlanner planner = mock(MemoryRetrievalPlanner.class);
    private final SafetyProfileBuilder safetyProfileBuilder = mock(SafetyProfileBuilder.class);
    private final SessionCheckpointRepository checkpointRepository = mock(SessionCheckpointRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final WorkingMemory workingMemory = mock(WorkingMemory.class);
    private final OntologyRelationExpander ontologyRelationExpander = mock(OntologyRelationExpander.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private final org.springframework.data.redis.core.ValueOperations<String, String> valueOperations =
            mock(org.springframework.data.redis.core.ValueOperations.class);

    private ContextPreWarmer preWarmer;
    private UUID sessionId;
    private UUID userId;
    private CombinedSignal combined;
    private SafetyProfile profile;

    @BeforeEach
    void setUp() {
        preWarmer = new ContextPreWarmer(structuredRetriever, vectorRetriever, lexicalRetriever, embeddingClient,
                fusionRanker, contextComposer, planner, safetyProfileBuilder, checkpointRepository,
                redisTemplate, jdbcTemplate, workingMemory, ontologyRelationExpander, meterRegistry);
        // Spring 컨텍스트 없이 생성하므로 @PostConstruct 가 돌지 않는다.
        preWarmer.initEmbeddingWaitTimers();
        sessionId = UUID.randomUUID();
        userId = UUID.randomUUID();
        combined = mock(CombinedSignal.class);
        profile = mock(SafetyProfile.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq(userId))).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /** 사전 웜업이 조립까지 도달하도록 계획·랭킹을 채운다. 위험도별 변형 검증에만 쓴다. */
    private void stubPlanAndRanking() {
        RetrievedItem item = new RetrievedItem("e-1", RetrievalSource.LEXICAL_EPISODE,
                "지난주 불안", "normal", 0.9, 1);
        when(fusionRanker.rank(any(), any(), anyInt())).thenReturn(List.of(item));
    }

    /**
     * 위기 턴의 캐시 폴백이 highRisk 필터를 거친 내용만 쓴다 (이슈 #522).
     *
     * <p>사전 웜업은 사용자의 다음 발화를 모르므로 위험도를 알 수 없다. 그래서 지금까지
     * {@code highRisk=false} 하나로만 구웠고, 라이브 검색이 비어 폴백이 채택되면
     * <b>에피소드·신념 기억이 필터를 거치지 않고</b> 위기 턴 프롬프트에 들어갔다.
     *
     * <p>모르는 값은 굽는 시점에 정할 수 없으니 <b>두 변형을 모두 굽는다.</b> 채택 시점에
     * 실제 위험도로 고른다. 조립은 문자열 빌드뿐이라 비용이 사실상 없다.
     */
    @Test
    @DisplayName("사전 웜업은 위험도별 두 변형을 모두 굽는다")
    void preWarmBakesBothRiskVariants() {
        stubPlanAndRanking();
        when(contextComposer.compose(anyList(), any(), eq(false))).thenReturn("일반 문맥");
        when(contextComposer.compose(anyList(), any(), eq(true))).thenReturn("위기 문맥");

        preWarmer.preWarm(sessionId, userId);

        verify(valueOperations).set(
                eq("session:%s:context_cache".formatted(sessionId)), eq("일반 문맥"), any(Duration.class));
        verify(valueOperations).set(
                eq("session:%s:context_cache:high_risk".formatted(sessionId)), eq("위기 문맥"), any(Duration.class));
    }

    /**
     * 채택 측이 위험도를 스스로 해석하지 않는다 (이슈 #522).
     *
     * <p>굽는 쪽과 채택하는 쪽이 각자 {@code highRisk} 를 계산하면 정의가 갈라질 수 있고,
     * 갈라지는 순간 이 결함이 그대로 돌아온다. 판정을 {@link ContextPreWarmer} 하나가 갖고
     * 호출자는 {@link CombinedSignal} 만 넘긴다.
     */
    @Test
    @DisplayName("위기 신호면 highRisk 변형을 읽는다")
    void cachedContextPicksTheVariantMatchingTheSignal() {
        when(combined.hardCrisis()).thenReturn(true);
        when(valueOperations.get("session:%s:context_cache:high_risk".formatted(sessionId)))
                .thenReturn("위기 문맥");

        assertThat(preWarmer.getCachedContext(sessionId, combined)).isEqualTo("위기 문맥");
    }

    @Test
    @DisplayName("위험 후보도 highRisk 변형을 읽는다 — 굽는 쪽과 같은 판정을 쓴다")
    void riskCandidateAlsoPicksHighRiskVariant() {
        when(combined.hardCrisis()).thenReturn(false);
        when(combined.riskCandidate()).thenReturn(true);
        when(valueOperations.get("session:%s:context_cache:high_risk".formatted(sessionId)))
                .thenReturn("위기 문맥");

        assertThat(preWarmer.getCachedContext(sessionId, combined)).isEqualTo("위기 문맥");
    }

    @Test
    @DisplayName("일반 턴은 일반 변형을 읽는다")
    void normalTurnPicksNormalVariant() {
        when(combined.hardCrisis()).thenReturn(false);
        when(combined.riskCandidate()).thenReturn(false);
        when(valueOperations.get("session:%s:context_cache".formatted(sessionId)))
                .thenReturn("일반 문맥");

        assertThat(preWarmer.getCachedContext(sessionId, combined)).isEqualTo("일반 문맥");
    }

    /**
     * highRisk 변형이 없으면 일반 변형으로 내려가지 않는다 (이슈 #522).
     *
     * <p>TTL 이 지나거나 이 수정 배포 전에 구워진 캐시가 남아 있을 수 있다. 그때 일반 변형을
     * 대신 쓰면 필터 미적용 내용이 위기 턴에 들어가는 원래 결함이 된다. 기억 없이 가는 편이
     * 필터 안 된 기억보다 안전하다.
     */
    @Test
    @DisplayName("highRisk 변형이 없으면 일반 변형으로 대체하지 않는다")
    void missingHighRiskVariantDoesNotFallBackToTheNormalOne() {
        when(combined.hardCrisis()).thenReturn(true);
        when(valueOperations.get("session:%s:context_cache:high_risk".formatted(sessionId)))
                .thenReturn(null);
        when(valueOperations.get("session:%s:context_cache".formatted(sessionId)))
                .thenReturn("필터 안 된 문맥");

        assertThat(preWarmer.getCachedContext(sessionId, combined)).isNull();
    }

    /**
     * 캐시 나이를 노출한다 (이슈 #522).
     *
     * <p>폴백이 얼마나 낡은 문맥을 주입했는지 알 수 없으면 이 경로의 품질을 사후에 판정할
     * 수 없다. 남은 TTL 로 나이를 역산한다 — 값 형식을 바꾸지 않아도 된다.
     */
    @Test
    @DisplayName("남은 TTL 로 캐시 나이를 역산한다")
    void reportsCacheAgeFromRemainingTtl() {
        when(combined.hardCrisis()).thenReturn(false);
        when(combined.riskCandidate()).thenReturn(false);
        when(redisTemplate.getExpire("session:%s:context_cache".formatted(sessionId), TimeUnit.MILLISECONDS))
                .thenReturn(200_000L);

        assertThat(preWarmer.getCachedContextAgeMs(sessionId, combined)).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("TTL 을 못 읽으면 나이는 null — 0 으로 보고하지 않는다")
    void unknownTtlYieldsNullAgeRatherThanZero() {
        when(combined.hardCrisis()).thenReturn(false);
        when(combined.riskCandidate()).thenReturn(false);
        when(redisTemplate.getExpire("session:%s:context_cache".formatted(sessionId), TimeUnit.MILLISECONDS))
                .thenReturn(-2L);

        assertThat(preWarmer.getCachedContextAgeMs(sessionId, combined)).isNull();
    }

    @Test
    void retrievesVectorAndLexicalEpisodesForCurrentMessage() {
        RetrievalPlan plan = new RetrievalPlan(
                List.of(RetrievalSource.VECTOR_EPISODE, RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        float[] embedding = {0.1f, 0.2f};
        RetrievedItem episode = new RetrievedItem("episode-1", RetrievalSource.VECTOR_EPISODE,
                "회의가 불안했던 날", "normal", 0.9, 1);
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(embeddingClient.embed(eq("회의 때문에 불안해"), any(), any(), any())).thenReturn(embedding);
        when(vectorRetriever.retrieveEpisodes(userId, embedding, 3)).thenReturn(List.of(episode));
        when(lexicalRetriever.retrieveByKeywords(userId, "회의 때문에 불안해", 3)).thenReturn(List.of());
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(episode));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("live memory");

        MemoryContextResult context = preWarmer.buildContextSync(sessionId, userId, combined, profile, "회의 때문에 불안해");

        assertThat(context.text()).isEqualTo("live memory");
        verify(embeddingClient).embed(eq("회의 때문에 불안해"), any(), any(), any());
        verify(vectorRetriever).retrieveEpisodes(userId, embedding, 3);
        verify(lexicalRetriever).retrieveByKeywords(userId, "회의 때문에 불안해", 3);
    }

    @Test
    void fallsBackToLexicalRetrievalWhenEmbeddingFails() {
        RetrievalPlan plan = new RetrievalPlan(
                List.of(RetrievalSource.VECTOR_EPISODE, RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        RetrievedItem episode = new RetrievedItem("episode-2", RetrievalSource.LEXICAL_EPISODE,
                "회의 전 긴장", "normal", 0.7, 1);
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(embeddingClient.embed(eq("회의가 걱정돼"), any(), any(), any())).thenThrow(new RuntimeException("timeout"));
        when(lexicalRetriever.retrieveByKeywords(userId, "회의가 걱정돼", 3)).thenReturn(List.of(episode));
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(episode));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("lexical memory");

        MemoryContextResult context = preWarmer.buildContextSync(sessionId, userId, combined, profile, "회의가 걱정돼");

        assertThat(context.text()).isEqualTo("lexical memory");
        verify(vectorRetriever, never()).retrieveEpisodes(any(), any(), any(Integer.class));
        verify(lexicalRetriever).retrieveByKeywords(userId, "회의가 걱정돼", 3);
    }

    @Test
    void limitsEmbeddingWaitAndContinuesWithLexicalRetrieval() throws Exception {
        RetrievalPlan plan = new RetrievalPlan(
                List.of(RetrievalSource.VECTOR_EPISODE, RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        RetrievedItem episode = new RetrievedItem("episode-3", RetrievalSource.LEXICAL_EPISODE,
                "발표 전 긴장", "normal", 0.7, 1);
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(embeddingClient.embed(eq("발표가 걱정돼"), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return new float[]{0.1f};
        });
        when(lexicalRetriever.retrieveByKeywords(userId, "발표가 걱정돼", 3)).thenReturn(List.of(episode));
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(episode));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("lexical memory");

        long startedAt = System.nanoTime();
        MemoryContextResult context = preWarmer.buildContextSync(sessionId, userId, combined, profile, "발표가 걱정돼");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(context.text()).isEqualTo("lexical memory");
        assertThat(elapsedMs).isLessThan(700);
        verify(lexicalRetriever).retrieveByKeywords(userId, "발표가 걱정돼", 3);
    }

    @Test
    void 대기_상한_초과는_일반_실패와_다른_outcome_으로_계측된다() {
        stubSlowEmbedding("상한 초과", 800);

        preWarmer.buildContextSync(sessionId, userId, combined, profile, "상한 초과");

        // 두 값이 합쳐져 있으면 지표만 보고 "외부 장애" 와 "상한이 짧다" 를 구별할 수 없다.
        assertThat(retrievalOutcomeCount("timeout")).isEqualTo(1);
        assertThat(retrievalOutcomeCount("failed")).isZero();
    }

    @Test
    void 일반_실패는_여전히_failed_로_계측된다() {
        RetrievalPlan plan = new RetrievalPlan(
                List.of(RetrievalSource.VECTOR_EPISODE, RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(embeddingClient.embed(eq("외부 장애"), any(), any(), any()))
                .thenThrow(new RuntimeException("upstream down"));
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of());
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("memory");

        preWarmer.buildContextSync(sessionId, userId, combined, profile, "외부 장애");

        assertThat(retrievalOutcomeCount("failed")).isEqualTo(1);
        assertThat(retrievalOutcomeCount("timeout")).isZero();
    }

    @Test
    void 버려진_호출의_왕복_시간도_기록된다() {
        stubSlowEmbedding("버려진 호출", 600);

        preWarmer.buildContextSync(sessionId, userId, combined, profile, "버려진 호출");

        // 호출자는 250ms 에 포기했지만 임베딩 자체는 계속 돌아 완료된다.
        // 그 표본이야말로 "상한을 얼마로 올려야 하는가" 의 유일한 근거다 —
        // 여기서 기록이 없으면 타임아웃 카운터만 남아 260ms 인지 3초인지 알 수 없다.
        // 성공·실패를 태그로 나눈다 — 상한을 정하는 근거는 성공한 호출의 분포다.
        awaitEmbeddingWaitSamples("success", 1L);
        assertThat(embeddingWaitTimer("success").totalTime(TimeUnit.MILLISECONDS))
                .as("버려진 호출의 실제 왕복 시간이 상한보다 크게 기록돼야 한다")
                .isGreaterThan(500);
    }

    @Test
    void 계측이_실패해도_임베딩_결과를_덮지_않는다() {
        RetrievalPlan plan = new RetrievalPlan(
                List.of(RetrievalSource.VECTOR_EPISODE, RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        float[] embedding = {0.1f, 0.2f};
        RetrievedItem episode = new RetrievedItem("episode-9", RetrievalSource.VECTOR_EPISODE,
                "계측 실패", "normal", 0.9, 1);
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(embeddingClient.embed(eq("계측 실패"), any(), any(), any())).thenReturn(embedding);
        when(vectorRetriever.retrieveEpisodes(userId, embedding, 3)).thenReturn(List.of(episode));
        when(lexicalRetriever.retrieveByKeywords(userId, "계측 실패", 3)).thenReturn(List.of());
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(episode));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("live memory");

        Timer broken = mock(Timer.class);
        doThrow(new IllegalStateException("metrics down")).when(broken).record(anyLong(), any());
        ReflectionTestUtils.setField(preWarmer, "embeddingWaitSuccessTimer", broken);

        MemoryContextResult context = preWarmer.buildContextSync(sessionId, userId, combined, profile, "계측 실패");

        // finally 안에서 예외가 새어 나가면 자바 의미론상 그 예외가 반환값을 대체한다.
        // 그러면 성공한 임베딩이 outcome=failed 로 오분류돼 버려진다 —
        // 이 클래스가 없애려는 문제를 관측 코드가 다시 만드는 셈이다.
        assertThat(context.text()).isEqualTo("live memory");
        verify(vectorRetriever).retrieveEpisodes(userId, embedding, 3);
        assertThat(retrievalOutcomeCount("failed")).isZero();
        assertThat(retrievalOutcomeCount("timeout")).isZero();
    }

    /** 상한(250ms)을 넘겨 완료되는 임베딩. 호출자는 포기하고 어휘 검색으로 계속한다. */
    private void stubSlowEmbedding(String queryText, long embedMillis) {
        RetrievalPlan plan = new RetrievalPlan(
                List.of(RetrievalSource.VECTOR_EPISODE, RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(embeddingClient.embed(eq(queryText), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(embedMillis);
            return new float[]{0.1f};
        });
        when(lexicalRetriever.retrieveByKeywords(userId, queryText, 3)).thenReturn(List.of());
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of());
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("memory");
    }

    private double retrievalOutcomeCount(String outcome) {
        Counter counter = meterRegistry.find("mio.retrieval.outcome")
                .tag("source", RetrievalSource.VECTOR_EPISODE.name())
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0d : counter.count();
    }

    private Timer embeddingWaitTimer(String outcome) {
        return meterRegistry.find("mio.retrieval.embedding.wait").tag("outcome", outcome).timer();
    }

    /** 임베딩은 호출자가 포기한 뒤에 끝나므로, 표본이 들어올 때까지 짧게 기다린다. */
    private void awaitEmbeddingWaitSamples(String outcome, long expected) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> assertThat(embeddingWaitTimer(outcome))
                        .as("버려진 호출의 왕복 시간 표본이 기록돼야 한다")
                        .isNotNull()
                        .extracting(Timer::count)
                        .isEqualTo(expected));
    }

    @Test
    void retrievesOnlyActivatedBeliefNeighborsForCurrentSession() {
        UUID beliefId = UUID.randomUUID();
        RetrievalPlan plan = new RetrievalPlan(List.of(RetrievalSource.GRAPH_BELIEF_NEIGH), 3, 200, "normal");
        SessionDelta delta = new SessionDelta(0, "none", java.util.Map.of(), 0,
                Set.of(beliefId.toString()), Set.of());
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(workingMemory.getSessionDelta(sessionId)).thenReturn(delta);
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of());
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("belief memory");

        MemoryContextResult context = preWarmer.buildContextSync(sessionId, userId, combined, profile, "회의가 걱정돼");

        assertThat(context.text()).isEqualTo("belief memory");
        verify(structuredRetriever).retrieveBeliefNeighbors(userId, Set.of(beliefId.toString()));
    }

    @Test
    void retrievesPastEpisodesForVerifiedCooccurringPatternsWithoutTreatingThemAsCurrent() {
        RetrievalPlan plan = new RetrievalPlan(List.of(RetrievalSource.GRAPH_DISTORTION), 3, 200, "normal");
        RetrievedItem related = new RetrievedItem("episode-1", RetrievalSource.GRAPH_DISTORTION,
                "related pattern (unconfirmed): 과거 업무 압박", "normal", 0.45, 1);
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(ontologyRelationExpander.expandCooccurringCodes("catastrophizing")).thenReturn(Set.of("mind_reading"));
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(related));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("related memory");

        MemoryContextResult context = preWarmer.buildContextSync(
                sessionId, userId, combined, profile, "회의가 걱정돼", "catastrophizing");

        assertThat(context.text()).isEqualTo("related memory");
        verify(structuredRetriever).retrieveRelatedDistortionEpisodes(userId, Set.of("mind_reading"));
    }

    // ── 실패 상태 전파 (이슈 #364, 로드맵 §12 P0-2 · §10.1) ──────────────────

    @Test
    void marksSourceFailedInsteadOfSilentlyReturningNoMemory() {
        RetrievalPlan plan = new RetrievalPlan(
                List.of(RetrievalSource.VECTOR_EPISODE, RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        RetrievedItem episode = new RetrievedItem("episode-9", RetrievalSource.LEXICAL_EPISODE,
                "회의 전 긴장", "normal", 0.7, 1);
        float[] embedding = new float[]{0.1f, 0.2f};
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(embeddingClient.embed(eq("회의가 걱정돼"), any(), any(), any())).thenReturn(embedding);
        // pgvector 만 죽는다. 나머지 소스는 정상이므로 턴은 완주해야 한다.
        when(vectorRetriever.retrieveEpisodes(userId, embedding, 3))
                .thenThrow(new RuntimeException("pgvector down"));
        when(lexicalRetriever.retrieveByKeywords(userId, "회의가 걱정돼", 3)).thenReturn(List.of(episode));
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(episode));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("lexical only");

        MemoryContextResult context = preWarmer.buildContextSync(
                sessionId, userId, combined, profile, "회의가 걱정돼");

        // 부분 실패다 — 남은 소스로 컨텍스트를 만들되 무엇이 죽었는지 남는다.
        assertThat(context.text()).isEqualTo("lexical only");
        assertThat(context.status()).isEqualTo(MemoryContextStatus.PARTIAL);
        assertThat(context.failedSources()).containsExactly(RetrievalSource.VECTOR_EPISODE);
        assertThat(context.degraded()).isTrue();
        assertThat(meterRegistry.counter("mio.retrieval.outcome",
                "source", "VECTOR_EPISODE", "outcome", "failed").count()).isEqualTo(1.0);
    }

    @Test
    void reportsOkWhenEverySourceAnsweredEvenIfNothingWasFound() {
        RetrievalPlan plan = new RetrievalPlan(List.of(RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(lexicalRetriever.retrieveByKeywords(userId, "평범한 하루", 3)).thenReturn(List.of());
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of());
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("");

        MemoryContextResult context = preWarmer.buildContextSync(
                sessionId, userId, combined, profile, "평범한 하루");

        // "기억이 없다" 는 정상이다. 이것을 실패로 세면 실패율이 의미를 잃는다.
        assertThat(context.status()).isEqualTo(MemoryContextStatus.OK);
        assertThat(context.failedSources()).isEmpty();
        assertThat(context.degraded()).isFalse();
    }

    @Test
    void reportsFailedRatherThanNullWhenContextAssemblyBreaks() {
        // 계획 수립 자체가 깨지면 검색을 돌릴 수 없다.
        when(planner.plan(any(), any(), eq(userId), anyBoolean()))
                .thenThrow(new RuntimeException("planner down"));

        MemoryContextResult context = preWarmer.buildContextSync(
                sessionId, userId, combined, profile, "회의가 걱정돼");

        // null 을 돌려주면 호출부가 "기억 없음" 과 구별할 수 없다 (§10.1, 이슈 #356 과 같은 형태).
        assertThat(context).isNotNull();
        assertThat(context.status()).isEqualTo(MemoryContextStatus.FAILED);
        assertThat(context.text()).isNull();
    }

    @Test
    void keepsRetrievingWhenHistoryProbeFailsInsteadOfDroppingAllMemory() {
        RetrievalPlan plan = new RetrievalPlan(List.of(RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        RetrievedItem episode = new RetrievedItem("episode-4", RetrievalSource.LEXICAL_EPISODE,
                "회의 전 긴장", "normal", 0.7, 1);
        // COUNT 한 방이 실패했다고 멀쩡한 소스까지 버리면, 관측 가능성을 얻으려다 가용성을 잃는다.
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq(userId)))
                .thenThrow(new RuntimeException("transient blip"));
        // 이력이 있다고 가정해야 소스가 더 많은 계획이 선택된다.
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(lexicalRetriever.retrieveByKeywords(userId, "회의가 걱정돼", 3)).thenReturn(List.of(episode));
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(episode));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("still has memory");

        MemoryContextResult context = preWarmer.buildContextSync(
                sessionId, userId, combined, profile, "회의가 걱정돼");

        assertThat(context.text()).isEqualTo("still has memory");
        assertThat(context.status()).isEqualTo(MemoryContextStatus.PARTIAL);
        // 죽은 소스는 없지만 계획이 추측이었다는 사실은 남아야 한다.
        assertThat(context.failedSources()).isEmpty();
        assertThat(context.planDegraded()).isTrue();
    }

    @Test
    void marksGraphDistortionFailedWhenOntologyExpansionThrows() {
        RetrievalPlan plan = new RetrievalPlan(List.of(RetrievalSource.GRAPH_DISTORTION), 3, 200, "normal");
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(ontologyRelationExpander.expandCooccurringCodes("catastrophizing"))
                .thenThrow(new RuntimeException("ontology db down"));
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of());
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("");

        MemoryContextResult context = preWarmer.buildContextSync(
                sessionId, userId, combined, profile, "회의가 걱정돼", "catastrophizing");

        // 확장이 비면 그 소스는 아무것도 조회하지 않는다. 실패와 "동반 왜곡 없음" 이
        // 같아 보이면 검색기에서 고친 결함이 한 단계 앞에 남는다.
        assertThat(context.status()).isEqualTo(MemoryContextStatus.PARTIAL);
        assertThat(context.failedSources()).contains(RetrievalSource.GRAPH_DISTORTION);
    }

    @Test
    void treatsNullReturnFromSourceAsFailureRatherThanCrashingTheTurn() {
        RetrievalPlan plan = new RetrievalPlan(List.of(RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        // 예외를 던지지 않고 null 을 돌려주는 소스. 방어가 없으면 아래 filter 에서 NPE 가 나고
        // 소스 하나가 턴 전체를 죽인다 — 그것도 실패로 기록되지 않은 채.
        when(lexicalRetriever.retrieveByKeywords(userId, "회의가 걱정돼", 3)).thenReturn(null);
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of());
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("");

        MemoryContextResult context = preWarmer.buildContextSync(
                sessionId, userId, combined, profile, "회의가 걱정돼");

        assertThat(context.status()).isEqualTo(MemoryContextStatus.PARTIAL);
        assertThat(context.failedSources()).contains(RetrievalSource.LEXICAL_EPISODE);
    }

    @Test
    void marksVectorEpisodeFailedWhenEmbeddingTimesOut() {
        RetrievalPlan plan = new RetrievalPlan(
                List.of(RetrievalSource.VECTOR_EPISODE, RetrievalSource.LEXICAL_EPISODE), 3, 200, "normal");
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(embeddingClient.embed(eq("발표가 걱정돼"), any(), any(), any()))
                .thenThrow(new RuntimeException("embedding timeout"));
        when(lexicalRetriever.retrieveByKeywords(userId, "발표가 걱정돼", 3)).thenReturn(List.of());
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of());
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("");

        MemoryContextResult context = preWarmer.buildContextSync(
                sessionId, userId, combined, profile, "발표가 걱정돼");

        // 임베딩이 죽으면 벡터 검색은 실행조차 되지 않는다. 그것도 실패로 세야
        // "임베딩 타임아웃이 잦다" 를 관측할 수 있다.
        assertThat(context.status()).isEqualTo(MemoryContextStatus.PARTIAL);
        assertThat(context.failedSources()).contains(RetrievalSource.VECTOR_EPISODE);
        verify(vectorRetriever, never()).retrieveEpisodes(any(), any(), any(Integer.class));
    }
}
