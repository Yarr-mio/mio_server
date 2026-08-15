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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
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
        sessionId = UUID.randomUUID();
        userId = UUID.randomUUID();
        combined = mock(CombinedSignal.class);
        profile = mock(SafetyProfile.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq(userId))).thenReturn(1);
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
