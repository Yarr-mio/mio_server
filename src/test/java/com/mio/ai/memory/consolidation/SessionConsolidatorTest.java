package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.memory.episodic.ThoughtRepository;
import com.mio.ai.memory.episodic.UserBelief;
import com.mio.ai.memory.episodic.UserBeliefRepository;
import com.mio.ai.memory.ontology.OntologyValidator;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.session.domain.SummaryStatus;
import com.mio.session.repository.SessionCheckpointRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

class SessionConsolidatorTest {

    private MessageEncryptor messageEncryptor;
    private BeliefIdentityHasher beliefIdentityHasher;
    private ThoughtRepository thoughtRepository;
    private UserBeliefRepository beliefRepository;
    private BeliefEvidenceAccumulator evidenceAccumulator;
    private JdbcTemplate jdbcTemplate;
    private SessionRepository sessionRepository;
    private TodoRecommendationService todoRecommendationService;
    private SummaryStatusWriter summaryStatusWriter;
    private ObjectProvider<SessionConsolidator> self;

    private SessionConsolidator newConsolidator() {
        messageEncryptor = mock(MessageEncryptor.class);
        beliefIdentityHasher = mock(BeliefIdentityHasher.class);
        thoughtRepository = mock(ThoughtRepository.class);
        beliefRepository = mock(UserBeliefRepository.class);
        evidenceAccumulator = mock(BeliefEvidenceAccumulator.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        sessionRepository = mock(SessionRepository.class);
        todoRecommendationService = mock(TodoRecommendationService.class);
        summaryStatusWriter = mock(SummaryStatusWriter.class);
        when(messageEncryptor.encrypt(any())).thenReturn(new byte[]{1});
        when(messageEncryptor.dekId()).thenReturn("app-key-v1");
        when(beliefIdentityHasher.hash(any(), anyString(), any())).thenReturn(new byte[]{9});

        @SuppressWarnings("unchecked")
        ObjectProvider<SessionConsolidator> selfProvider = mock(ObjectProvider.class);
        self = selfProvider;

        return new SessionConsolidator(
                sessionRepository,
                mock(SessionSummaryRepository.class),
                mock(SessionCheckpointRepository.class),
                mock(UserRepository.class),
                thoughtRepository,
                beliefRepository,
                evidenceAccumulator,
                mock(ExtractorLlmClient.class),
                mock(LlmClient.class),
                messageEncryptor,
                beliefIdentityHasher,
                jdbcTemplate,
                new ObjectMapper(),
                mock(OntologyValidator.class),
                todoRecommendationService,
                summaryStatusWriter,
                selfProvider
        );
    }

    private User userWithId() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        return user;
    }

    private ExtractorResult.ExtractedThought thought(String beliefKind, String polarity) {
        return new ExtractorResult.ExtractedThought("자동적 사고", "self_blame", beliefKind, polarity, 0.7);
    }

    @Test
    @DisplayName("세션 요약 DONE은 Todo 저장 완료 후에만 표시한다")
    void onSessionEnded_marksDoneOnlyAfterTodoGeneration() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "세션 요약"
        );
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 2)).thenReturn(input);
        when(todoRecommendationService.generateForSession(eq(userId), eq(sessionId), any()))
                .thenReturn(3);

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 2));

        InOrder inOrder = inOrder(proxy, todoRecommendationService, summaryStatusWriter);
        inOrder.verify(proxy).consolidate(sessionId, userId, "mio", 2);
        inOrder.verify(proxy).enrichMemory(input);
        inOrder.verify(todoRecommendationService).generateForSession(eq(userId), eq(sessionId), any());
        inOrder.verify(summaryStatusWriter).markDone(sessionId);
        verify(sessionRepository, never()).updateSummaryStatus(sessionId, SummaryStatus.DONE);
        verify(summaryStatusWriter, never()).markFailed(sessionId);
    }

    @Test
    @DisplayName("Todo를 만들지 못하면 빈 Todo 요약을 노출하지 않고 실패 상태로 전환한다")
    void onSessionEnded_whenTodoGenerationCreatesNoTasks_marksFailed() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "세션 요약"
        );
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 2)).thenReturn(input);
        when(todoRecommendationService.generateForSession(eq(userId), eq(sessionId), any()))
                .thenReturn(0);

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 2));

        verify(summaryStatusWriter, never()).markDone(sessionId);
        verify(summaryStatusWriter).markFailed(sessionId);
    }

    @Test
    @DisplayName("세션 종료 요약은 최근 40개로 자르지 않고 전체 대화를 시간순으로 조회한다")
    void loadConversationLines_does_not_limit_to_recent_40_messages() {
        SessionConsolidator consolidator = newConsolidator();
        UUID sessionId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(sessionId))).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(consolidator, "loadConversationLines", sessionId);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(sessionId));
        assertThat(sqlCaptor.getValue().toLowerCase()).doesNotContain("limit 40");
        assertThat(sqlCaptor.getValue().toLowerCase()).contains("order by created_at asc");
    }

    @Test
    @DisplayName("ExtractorLLM이 문자열 \"null\" beliefKind를 반환하면 belief를 만들지 않고 예외도 없다")
    void persistThought_stringNullBeliefKind_skipsBeliefWithoutError() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(
                consolidator, "persistThought", user, UUID.randomUUID(), thought("null", "negative")
        )).doesNotThrowAnyException();

        verify(thoughtRepository).save(any());
        // 허용값 밖 beliefKind는 belief 영속 경로에 진입하지 않아 DB CHECK 위반을 원천 차단한다.
        verifyNoInteractions(beliefRepository);
        verifyNoInteractions(evidenceAccumulator);
    }

    @Test
    @DisplayName("시드에 없는 환각 beliefKind도 걸러내고 belief를 만들지 않는다")
    void persistThought_unknownBeliefKind_skipsBelief() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();

        ReflectionTestUtils.invokeMethod(
                consolidator, "persistThought", user, UUID.randomUUID(), thought("core_belief", "negative"));

        verify(beliefRepository, never()).save(any());
        verifyNoInteractions(evidenceAccumulator);
    }

    @Test
    @DisplayName("허용된 beliefKind는 정상적으로 UserBelief를 생성한다")
    void persistThought_validBeliefKind_createsBelief() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();
        UUID sessionId = UUID.randomUUID();
        when(beliefRepository.findByUser_IdAndStatus(any(), eq("active"))).thenReturn(List.of());
        when(beliefRepository.save(any(UserBelief.class))).thenAnswer(inv -> inv.getArgument(0));

        ReflectionTestUtils.invokeMethod(
                consolidator, "persistThought", user, sessionId, thought("core_self", "negative"));

        ArgumentCaptor<UserBelief> captor = ArgumentCaptor.forClass(UserBelief.class);
        verify(beliefRepository).save(captor.capture());
        assertThat(captor.getValue().getBeliefKind()).isEqualTo("core_self");
        assertThat(captor.getValue().getPolarity()).isEqualTo("negative");
        verify(evidenceAccumulator).accumulate(any(), eq("support"), eq(sessionId), eq(null));
    }

    @Test
    @DisplayName("동일한 종류와 극성이라도 다른 신념 식별자는 병합하지 않는다")
    void persistThought_differentIdentityCreatesSeparateBelief() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();
        UUID sessionId = UUID.randomUUID();
        when(beliefRepository.findByUser_IdAndStatusAndBeliefIdentityVersionAndBeliefIdentityHash(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        when(beliefRepository.save(any(UserBelief.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(consolidator, "persistThought", user, sessionId,
                new ExtractorResult.ExtractedThought("나는 아무것도 못해", "self_blame", "core_self", "negative",
                        0.7, "나는 능력이 부족하다", "support"));

        verify(beliefRepository).save(any(UserBelief.class));
        verify(evidenceAccumulator).accumulate(any(), eq(BeliefEvidenceKind.SUPPORT), eq(sessionId), any());
    }

    @Test
    @DisplayName("반증 또는 재구성만 있는 새 신념은 만들지 않는다")
    void persistThought_contradictionWithoutExistingIdentityDoesNotCreateBelief() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();
        when(beliefRepository.findByUser_IdAndStatusAndBeliefIdentityVersionAndBeliefIdentityHash(
                any(), any(), any(), any())).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(consolidator, "persistThought", user, UUID.randomUUID(),
                new ExtractorResult.ExtractedThought("이번에는 해냈어", "self_blame", "core_self", "negative",
                        0.7, "나는 능력이 부족하다", "contradict"));

        verify(beliefRepository, never()).save(any(UserBelief.class));
        verifyNoInteractions(evidenceAccumulator);
    }

    @Test
    @DisplayName("세션을 찾지 못하면 요약을 영속화하지 않고 DONE으로 표시하지 않는다")
    void consolidate_sessionNotFound_doesNotMarkDone() {
        SessionConsolidator consolidator = newConsolidator();
        when(sessionRepository.findById(any())).thenReturn(Optional.empty());

        SessionConsolidator.EnrichmentInput result =
                consolidator.consolidate(UUID.randomUUID(), UUID.randomUUID(), "char-1", 0);

        assertThat(result).isNull();
        // 요약 row가 없는데 DONE으로 표시되면 조회 시 404가 발생하므로, 상태를 건드리지 않아야 한다.
        verify(sessionRepository, never()).updateSummaryStatus(any(), any());
    }

    @Test
    @DisplayName("허용값 밖 polarity는 null로 정규화되어 DB CHECK 위반을 막는다")
    void persistThought_invalidPolarity_normalizedToNull() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();
        when(beliefRepository.findByUser_IdAndStatus(any(), eq("active"))).thenReturn(List.of());
        when(beliefRepository.save(any(UserBelief.class))).thenAnswer(inv -> inv.getArgument(0));

        ReflectionTestUtils.invokeMethod(
                consolidator, "persistThought", user, UUID.randomUUID(), thought("core_self", "null"));

        ArgumentCaptor<UserBelief> captor = ArgumentCaptor.forClass(UserBelief.class);
        verify(beliefRepository).save(captor.capture());
        assertThat(captor.getValue().getPolarity()).isNull();
    }
}
