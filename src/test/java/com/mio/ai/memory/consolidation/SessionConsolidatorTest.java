package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.memory.episodic.ThoughtRepository;
import com.mio.ai.memory.episodic.UserBelief;
import com.mio.ai.memory.episodic.UserBeliefRepository;
import com.mio.ai.memory.ontology.OntologyValidator;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.session.repository.SessionCheckpointRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
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

class SessionConsolidatorTest {

    private MessageEncryptor messageEncryptor;
    private ThoughtRepository thoughtRepository;
    private UserBeliefRepository beliefRepository;
    private BeliefEvidenceAccumulator evidenceAccumulator;
    private JdbcTemplate jdbcTemplate;

    private SessionConsolidator newConsolidator() {
        messageEncryptor = mock(MessageEncryptor.class);
        thoughtRepository = mock(ThoughtRepository.class);
        beliefRepository = mock(UserBeliefRepository.class);
        evidenceAccumulator = mock(BeliefEvidenceAccumulator.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(messageEncryptor.encrypt(any())).thenReturn(new byte[]{1});
        when(messageEncryptor.dekId()).thenReturn("app-key-v1");

        @SuppressWarnings("unchecked")
        ObjectProvider<SessionConsolidator> self = mock(ObjectProvider.class);

        return new SessionConsolidator(
                mock(SessionRepository.class),
                mock(SessionSummaryRepository.class),
                mock(SessionCheckpointRepository.class),
                mock(UserRepository.class),
                thoughtRepository,
                beliefRepository,
                evidenceAccumulator,
                mock(ExtractorLlmClient.class),
                mock(LlmClient.class),
                messageEncryptor,
                jdbcTemplate,
                new ObjectMapper(),
                mock(OntologyValidator.class),
                mock(TodoRecommendationService.class),
                mock(SummaryStatusWriter.class),
                self
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
