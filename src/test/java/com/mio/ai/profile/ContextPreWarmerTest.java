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
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.safety.CombinedSignal;
import com.mio.session.repository.SessionCheckpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private ContextPreWarmer preWarmer;
    private UUID sessionId;
    private UUID userId;
    private CombinedSignal combined;
    private SafetyProfile profile;

    @BeforeEach
    void setUp() {
        preWarmer = new ContextPreWarmer(structuredRetriever, vectorRetriever, lexicalRetriever, embeddingClient,
                fusionRanker, contextComposer, planner, safetyProfileBuilder, checkpointRepository,
                redisTemplate, jdbcTemplate, workingMemory);
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
        when(embeddingClient.embed("회의 때문에 불안해")).thenReturn(embedding);
        when(vectorRetriever.retrieveEpisodes(userId, embedding, 3)).thenReturn(List.of(episode));
        when(lexicalRetriever.retrieveByKeywords(userId, "회의 때문에 불안해", 3)).thenReturn(List.of());
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(episode));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("live memory");

        String context = preWarmer.buildContextSync(sessionId, userId, combined, profile, "회의 때문에 불안해");

        assertThat(context).isEqualTo("live memory");
        verify(embeddingClient).embed("회의 때문에 불안해");
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
        when(embeddingClient.embed("회의가 걱정돼")).thenThrow(new RuntimeException("timeout"));
        when(lexicalRetriever.retrieveByKeywords(userId, "회의가 걱정돼", 3)).thenReturn(List.of(episode));
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(episode));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("lexical memory");

        String context = preWarmer.buildContextSync(sessionId, userId, combined, profile, "회의가 걱정돼");

        assertThat(context).isEqualTo("lexical memory");
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
        when(embeddingClient.embed("발표가 걱정돼")).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return new float[]{0.1f};
        });
        when(lexicalRetriever.retrieveByKeywords(userId, "발표가 걱정돼", 3)).thenReturn(List.of(episode));
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(episode));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("lexical memory");

        long startedAt = System.nanoTime();
        String context = preWarmer.buildContextSync(sessionId, userId, combined, profile, "발표가 걱정돼");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(context).isEqualTo("lexical memory");
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

        String context = preWarmer.buildContextSync(sessionId, userId, combined, profile, "회의가 걱정돼");

        assertThat(context).isEqualTo("belief memory");
        verify(structuredRetriever).retrieveBeliefNeighbors(userId, Set.of(beliefId.toString()));
    }

    @Test
    void retrievesPastEpisodesForVerifiedCooccurringPatternsWithoutTreatingThemAsCurrent() {
        RetrievalPlan plan = new RetrievalPlan(List.of(RetrievalSource.GRAPH_DISTORTION), 3, 200, "normal");
        RetrievedItem related = new RetrievedItem("episode-1", RetrievalSource.GRAPH_DISTORTION,
                "related pattern (unconfirmed): 과거 업무 압박", "normal", 0.45, 1);
        when(planner.plan(combined, profile, userId, true)).thenReturn(plan);
        when(fusionRanker.rank(any(), eq("normal"), eq(9))).thenReturn(List.of(related));
        when(contextComposer.compose(any(), eq("normal"), eq(false))).thenReturn("related memory");

        String context = preWarmer.buildContextSync(
                sessionId, userId, combined, profile, "회의가 걱정돼", "catastrophizing");

        assertThat(context).isEqualTo("related memory");
        verify(structuredRetriever).retrieveRelatedDistortionEpisodes(userId, Set.of("mind_reading"));
    }
}
