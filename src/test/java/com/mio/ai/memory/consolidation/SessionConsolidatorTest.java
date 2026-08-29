package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.crisis.CrisisEpisodePromoter;
import com.mio.ai.crisis.CrisisTodoDecision;
import com.mio.ai.crisis.CrisisTodoSafetyGate;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.memory.episodic.ThoughtRepository;
import com.mio.ai.memory.episodic.UserBelief;
import com.mio.ai.memory.episodic.UserBeliefRepository;
import com.mio.ai.memory.ontology.OntologyValidator;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.session.domain.SummaryStatus;
import com.mio.session.domain.Session;
import com.mio.session.repository.SessionCheckpointRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

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
    private SummaryComponentStatusWriter componentStatusWriter;
    private SessionSummaryRenderer sessionSummaryRenderer;
    private UserSummaryWriter userSummaryWriter;
    private CrisisEpisodePromoter crisisEpisodePromoter;
    private CrisisTodoSafetyGate crisisTodoSafetyGate;
    private MemoryConsentChecker memoryConsentChecker;
    private ObjectProvider<SessionConsolidator> self;
    private SimpleMeterRegistry meterRegistry;
    private SessionSummaryRepository sessionSummaryRepository;
    private SessionCheckpointRepository checkpointRepository;
    private UserRepository userRepository;
    private ExtractorLlmClient extractorLlmClient;
    private LlmClient llmClient;
    private OntologyValidator ontologyValidator;

    private SessionConsolidator newConsolidator() {
        messageEncryptor = mock(MessageEncryptor.class);
        beliefIdentityHasher = mock(BeliefIdentityHasher.class);
        thoughtRepository = mock(ThoughtRepository.class);
        beliefRepository = mock(UserBeliefRepository.class);
        evidenceAccumulator = mock(BeliefEvidenceAccumulator.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        sessionRepository = mock(SessionRepository.class);
        sessionSummaryRepository = mock(SessionSummaryRepository.class);
        checkpointRepository = mock(SessionCheckpointRepository.class);
        userRepository = mock(UserRepository.class);
        extractorLlmClient = mock(ExtractorLlmClient.class);
        llmClient = mock(LlmClient.class);
        ontologyValidator = mock(OntologyValidator.class);
        todoRecommendationService = mock(TodoRecommendationService.class);
        summaryStatusWriter = mock(SummaryStatusWriter.class);
        componentStatusWriter = mock(SummaryComponentStatusWriter.class);
        sessionSummaryRenderer = mock(SessionSummaryRenderer.class);
        userSummaryWriter = mock(UserSummaryWriter.class);
        crisisEpisodePromoter = mock(CrisisEpisodePromoter.class);
        crisisTodoSafetyGate = mock(CrisisTodoSafetyGate.class);
        when(crisisTodoSafetyGate.evaluate(any(), any()))
                .thenReturn(new CrisisTodoDecision(false, "no_crisis_evidence"));
        memoryConsentChecker = mock(MemoryConsentChecker.class);
        // 기본은 동의 유지 — 게이트 테스트에서만 철회로 뒤집는다.
        when(memoryConsentChecker.isRetentionAllowed(any())).thenReturn(true);
        meterRegistry = new SimpleMeterRegistry();
        when(messageEncryptor.encrypt(any())).thenReturn(new byte[]{1});
        when(messageEncryptor.dekId()).thenReturn("app-key-v1");
        when(beliefIdentityHasher.hash(any(), anyString(), anyShort())).thenReturn(new byte[]{9});

        @SuppressWarnings("unchecked")
        ObjectProvider<SessionConsolidator> selfProvider = mock(ObjectProvider.class);
        self = selfProvider;

        return new SessionConsolidator(
                sessionRepository,
                sessionSummaryRepository,
                checkpointRepository,
                userRepository,
                thoughtRepository,
                beliefRepository,
                evidenceAccumulator,
                extractorLlmClient,
                ModelCatalog.defaults(),
                llmClient,
                messageEncryptor,
                beliefIdentityHasher,
                jdbcTemplate,
                new ObjectMapper(),
                ontologyValidator,
                todoRecommendationService,
                sessionSummaryRenderer,
                userSummaryWriter,
                summaryStatusWriter,
                componentStatusWriter,
                crisisEpisodePromoter,
                crisisTodoSafetyGate,
                new SummaryStageMetrics(meterRegistry),
                memoryConsentChecker,
                meterRegistry,
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
    @DisplayName("핵심 요약은 Todo보다 먼저 DONE으로 공개한다")
    void onSessionEnded_marksCoreSummaryDoneBeforeTodoGeneration() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "세션 요약", "regular");
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 2)).thenReturn(input);
        when(todoRecommendationService.generateForSession(eq(userId), eq(sessionId), any()))
                .thenReturn(3);
        when(sessionSummaryRenderer.render("세션 요약", "mio", userId, sessionId))
                .thenReturn("사용자용 요약");

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 2));

        InOrder inOrder = inOrder(proxy, todoRecommendationService, summaryStatusWriter);
        inOrder.verify(summaryStatusWriter).markProcessingStarted(sessionId);
        inOrder.verify(proxy).consolidate(sessionId, userId, "mio", 2);
        inOrder.verify(summaryStatusWriter).markDone(sessionId);
        inOrder.verify(proxy).enrichMemory(input);
        inOrder.verify(todoRecommendationService).generateForSession(eq(userId), eq(sessionId), any());
        verify(sessionRepository, never()).updateSummaryStatus(sessionId, SummaryStatus.DONE);
        verify(summaryStatusWriter, never()).markFailed(sessionId);
        verify(componentStatusWriter).markTodoDone(sessionId);
        assertThat(timerCount("core_summary_ready", "done")).isEqualTo(1);
        assertThat(timerCount("memory_enrichment", "done")).isEqualTo(1);
        assertThat(timerCount("user_render", "done")).isEqualTo(1);
        assertThat(timerCount("todo_generation", "done")).isEqualTo(1);

        // 승격 호출이 통째로 빠져도 나머지 단언은 통과한다 — 배선 자체를 고정한다 (이슈 #256).
        verify(crisisEpisodePromoter).promoteIfCrisis(userId, sessionId, "regular");
    }

    @Test
    @DisplayName("메모리 동의를 철회한 사용자는 컨솔리데이션이 시작 전에 차단된다 (이슈 #453)")
    void onSessionEnded_skipsConsolidationWhenConsentWithdrawn() {
        SessionConsolidator consolidator = newConsolidator();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(memoryConsentChecker.isRetentionAllowed(userId)).thenReturn(false);

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 0));

        verify(summaryStatusWriter).markFailed(sessionId);
        // 게이트는 processing 시작 표시보다도 앞이다 — 철회 세션은 처리를 시작조차 하지 않는다
        verify(summaryStatusWriter, never()).markProcessingStarted(sessionId);
        // LLM 요약·보강·위기 승격·Todo 생성 어느 것도 시작되지 않아야 한다
        verifyNoInteractions(self);
        verifyNoInteractions(crisisEpisodePromoter);
        verifyNoInteractions(todoRecommendationService);
        verifyNoInteractions(componentStatusWriter);
        // 동의 기인 스킵은 실제 실패와 구분되는 전용 카운터로 남는다
        assertThat(meterRegistry.counter("mio.summary.consent.skip", "stage", "gate").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("사용자 노출용 요약은 내부 요약과 세션 캐릭터로 렌더링해 저장한다")
    void onSessionEnded_persistsRenderedUserSummary() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "내부 요약", "regular");
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "chichi", 2)).thenReturn(input);
        when(todoRecommendationService.generateForSession(eq(userId), eq(sessionId), any())).thenReturn(3);
        when(sessionSummaryRenderer.render("내부 요약", "chichi", userId, sessionId)).thenReturn("오늘 이야기 정리해봤어요.");

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "chichi", 2));

        verify(sessionSummaryRenderer).render("내부 요약", "chichi", userId, sessionId);
        verify(userSummaryWriter).write(sessionId, "오늘 이야기 정리해봤어요.");
        verify(summaryStatusWriter).markDone(sessionId);
        verify(componentStatusWriter).markUserRenderDone(sessionId);
    }

    @Test
    @DisplayName("렌더링이 계약 위반으로 비면 저장을 건너뛰고 요약은 그대로 노출한다")
    void onSessionEnded_whenRenderReturnsNull_skipsWriteButKeepsSummary() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "내부 요약", "regular");
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 2)).thenReturn(input);
        when(todoRecommendationService.generateForSession(eq(userId), eq(sessionId), any())).thenReturn(3);
        when(sessionSummaryRenderer.render(any(), any(), any(), any())).thenReturn(null);

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 2));

        verifyNoInteractions(userSummaryWriter);
        verify(summaryStatusWriter).markDone(sessionId);
        verify(summaryStatusWriter, never()).markFailed(sessionId);
        verify(componentStatusWriter).markUserRenderFailed(sessionId, "CONTRACT_INVALID");
    }

    @Test
    @DisplayName("렌더링 결과가 공백이면 저장하지 않고 계약 실패로 종결한다")
    void onSessionEnded_whenRenderReturnsWhitespace_marksContractInvalid() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "내부 요약", "regular");
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 2)).thenReturn(input);
        when(todoRecommendationService.generateForSession(eq(userId), eq(sessionId), any())).thenReturn(1);
        when(sessionSummaryRenderer.render(any(), any(), any(), any())).thenReturn("   \n\t");

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 2));

        verifyNoInteractions(userSummaryWriter);
        verify(componentStatusWriter).markUserRenderFailed(sessionId, "CONTRACT_INVALID");
        verify(componentStatusWriter, never()).markUserRenderDone(sessionId);
        assertThat(timerCount("user_render", "failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("렌더링이 예외로 죽어도 요약과 Todo 흐름은 그대로 완료된다")
    void onSessionEnded_whenRenderThrows_summaryStillCompletes() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "내부 요약", "regular");
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 2)).thenReturn(input);
        when(todoRecommendationService.generateForSession(eq(userId), eq(sessionId), any())).thenReturn(3);
        when(sessionSummaryRenderer.render(any(), any(), any(), any())).thenThrow(new RuntimeException("LLM down"));

        assertThatCode(() -> consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 2)))
                .doesNotThrowAnyException();

        verify(summaryStatusWriter).markDone(sessionId);
        verify(summaryStatusWriter, never()).markFailed(sessionId);
        verify(componentStatusWriter).markUserRenderFailed(sessionId, "USER_RENDER_FAILED");
        assertThat(timerCount("user_render", "failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("Todo가 0건이어도 핵심 요약은 유지하고 Todo만 skipped로 남긴다")
    void onSessionEnded_whenTodoGenerationCreatesNoTasks_keepsSummaryAndSkipsTodo() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "세션 요약", "regular");
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 2)).thenReturn(input);
        when(todoRecommendationService.generateForSession(eq(userId), eq(sessionId), any()))
                .thenReturn(0);

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 2));

        verify(summaryStatusWriter).markDone(sessionId);
        verify(summaryStatusWriter, never()).markFailed(sessionId);
        verify(componentStatusWriter).markTodoSkipped(sessionId);
    }

    @Test
    @DisplayName("Todo 생성 예외는 Todo만 failed로 남기고 핵심 요약을 봉인하지 않는다")
    void onSessionEnded_whenTodoGenerationThrows_keepsSummaryAndMarksTodoFailed() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "세션 요약", "regular");
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 2)).thenReturn(input);
        when(todoRecommendationService.generateForSession(eq(userId), eq(sessionId), any()))
                .thenThrow(new IllegalStateException("todo unavailable"));

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 2));

        verify(summaryStatusWriter).markDone(sessionId);
        verify(summaryStatusWriter, never()).markFailed(sessionId);
        verify(componentStatusWriter).markTodoFailed(sessionId, "TODO_GENERATION_FAILED");
        assertThat(timerCount("todo_generation", "failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("위기 세션은 Todo만 차단하고 이미 생성한 핵심 요약은 노출한다")
    void onSessionEnded_crisisSessionSuppressesTodoButKeepsSummary() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "위기 세션 요약", "crisis");
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 0)).thenReturn(input);
        when(crisisTodoSafetyGate.evaluate(userId, sessionId))
                .thenReturn(new CrisisTodoDecision(true, "crisis_event"));

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 0));

        verify(proxy).consolidate(sessionId, userId, "mio", 0);
        verify(proxy).enrichMemory(input);
        verify(crisisTodoSafetyGate).evaluate(userId, sessionId);
        verifyNoInteractions(todoRecommendationService);
        // 세션 수준 DONE 은 핵심 요약 커밋 직후 한 번만 — 위기 차단이 완료를 중복 계상하면
        // 요약 readiness 지표가 위기 세션 수만큼 부풀려진다.
        verify(summaryStatusWriter, times(1)).markDone(sessionId);
        verify(summaryStatusWriter, never()).markFailed(sessionId);
        // 차단된 것은 Todo 컴포넌트 하나뿐이므로 정상 경로의 "생성 0건" 과 같게 종결한다.
        verify(componentStatusWriter).markTodoSkipped(sessionId);
        assertThat(timerCount("todo_generation", "skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("요약할 대화가 없으면 pending 을 방치하지 않고 실패로 확정한다")
    void onSessionEnded_whenNothingToSummarize_marksFailed() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(self.getObject()).thenReturn(proxy);
        // 메시지 0건 세션·세션 부재·복호화 전량 실패는 모두 consolidate 가 null 을 반환한다.
        when(proxy.consolidate(sessionId, userId, "mio", 0)).thenReturn(null);

        consolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 0));

        // 상태를 그대로 두면 pending 에 영구 고착되어 요약 조회가 무한히 202 를 반환한다 (이슈 #356).
        verify(summaryStatusWriter).markFailed(sessionId);
        verify(summaryStatusWriter, never()).markDone(sessionId);
        verifyNoInteractions(todoRecommendationService, crisisEpisodePromoter, sessionSummaryRenderer);
        assertThat(timerCount("core_summary_ready", "failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("선제 인사만 있고 사용자 발화가 0건이면 요약·추출·Todo 를 만들지 않는다")
    void consolidate_openingOnlySession_skipsWithoutLlmCall() {
        SessionConsolidator consolidator = newConsolidator();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubCoreInputs(userId, sessionId);
        // 선제 인사(assistant) 한 건만 있는 세션 — 사용자 발화는 없다 (이슈 #530).
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(sessionId))).thenReturn(false);

        SessionConsolidator.EnrichmentInput result = consolidator.consolidate(sessionId, userId, "mio", 0);

        // 검수된 인사 한 문장으로 요약·감정·왜곡·Todo 를 만들면 사용자가 하지 않은 말에서
        // CBT 패턴을 뽑는 셈이고, LLM 비용도 같이 나간다.
        assertThat(result).isNull();
        verifyNoInteractions(llmClient, extractorLlmClient, todoRecommendationService);
        verify(sessionSummaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("사용자 발화 존재 확인이 실패하면 요약하지 않는다 — 근거 없이 진행하지 않는다")
    void consolidate_userMessageCheckFails_skips() {
        SessionConsolidator consolidator = newConsolidator();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubCoreInputs(userId, sessionId);
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(sessionId)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("statement timeout"));

        SessionConsolidator.EnrichmentInput result = consolidator.consolidate(sessionId, userId, "mio", 0);

        assertThat(result).isNull();
        verifyNoInteractions(llmClient, extractorLlmClient, todoRecommendationService);
    }

    @Test
    @DisplayName("핵심 요약 상태 저장이 실패하면 준비 완료 지연으로 집계하지 않는다")
    void onSessionEnded_whenCoreStatusWriteFails_recordsFailedReadiness() {
        SessionConsolidator consolidator = newConsolidator();
        SessionConsolidator proxy = mock(SessionConsolidator.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionConsolidator.EnrichmentInput input = new SessionConsolidator.EnrichmentInput(
                userId, sessionId, List.of(), null, List.of(), List.of(), "세션 요약", "regular");
        when(self.getObject()).thenReturn(proxy);
        when(proxy.consolidate(sessionId, userId, "mio", 2)).thenReturn(input);
        doAnswer(invocation -> {
            throw new IllegalStateException("status db unavailable");
        }).when(summaryStatusWriter).markDone(sessionId);

        assertThatThrownBy(() -> consolidator.onSessionEnded(
                new SessionEndedEvent(sessionId, userId, "mio", 2)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(timerCount("core_summary_ready", "failed")).isEqualTo(1);
        assertThat(timerCount("core_summary_ready", "done")).isZero();
        verifyNoInteractions(sessionSummaryRenderer, todoRecommendationService);
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
        UserBelief createdBelief = mock(UserBelief.class);
        when(beliefRepository.findByUser_IdAndStatusAndBeliefIdentityVersionAndBeliefIdentityHash(
                any(), eq("active"), anyShort(), any()))
                .thenReturn(Optional.empty(), Optional.of(createdBelief));

        ReflectionTestUtils.invokeMethod(
                consolidator, "persistThought", user, sessionId,
                new ExtractorResult.ExtractedThought("자동적 사고", "self_blame", "core_self", "negative",
                        0.7, "나는 충분하지 않다", "support"));

        verify(beliefRepository).insertActiveSemanticBeliefIfAbsent(
                eq(user.getId()), any(), eq("app-key-v1"), eq("core_self"), eq("negative"), any(), anyShort());
        verify(evidenceAccumulator).accumulate(
                eq(createdBelief), eq(BeliefEvidenceKind.SUPPORT), eq(sessionId), any());
    }

    @Test
    @DisplayName("동일한 종류와 극성이라도 다른 신념 식별자는 병합하지 않는다")
    void persistThought_differentIdentityCreatesSeparateBelief() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();
        UUID sessionId = UUID.randomUUID();
        UserBelief createdBelief = mock(UserBelief.class);
        when(beliefRepository.findByUser_IdAndStatusAndBeliefIdentityVersionAndBeliefIdentityHash(
                any(), eq("active"), anyShort(), any()))
                .thenReturn(Optional.empty(), Optional.of(createdBelief));

        ReflectionTestUtils.invokeMethod(consolidator, "persistThought", user, sessionId,
                new ExtractorResult.ExtractedThought("나는 아무것도 못해", "self_blame", "core_self", "negative",
                        0.7, "나는 능력이 부족하다", "support"));

        verify(beliefRepository).insertActiveSemanticBeliefIfAbsent(
                eq(user.getId()), any(), eq("app-key-v1"), eq("core_self"), eq("negative"), any(), anyShort());
        verify(evidenceAccumulator).accumulate(
                eq(createdBelief), eq(BeliefEvidenceKind.SUPPORT), eq(sessionId), any());
    }

    @Test
    @DisplayName("반증 또는 재구성만 있는 새 신념은 만들지 않는다")
    void persistThought_contradictionWithoutExistingIdentityDoesNotCreateBelief() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();
        when(beliefRepository.findByUser_IdAndStatusAndBeliefIdentityVersionAndBeliefIdentityHash(
                any(), eq("active"), anyShort(), any())).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(consolidator, "persistThought", user, UUID.randomUUID(),
                new ExtractorResult.ExtractedThought("이번에는 해냈어", "self_blame", "core_self", "negative",
                        0.7, "나는 능력이 부족하다", "contradict"));

        verify(beliefRepository, never()).save(any(UserBelief.class));
        verifyNoInteractions(evidenceAccumulator);
    }

    @Test
    @DisplayName("동일 식별자의 동시 생성 충돌은 기존 신념을 다시 찾아 증거를 연결한다")
    void persistThought_identityCreationRace_reusesExistingBelief() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();
        UserBelief existingBelief = mock(UserBelief.class);
        UUID sessionId = UUID.randomUUID();
        when(beliefRepository.findByUser_IdAndStatusAndBeliefIdentityVersionAndBeliefIdentityHash(
                any(), eq("active"), anyShort(), any()))
                .thenReturn(Optional.empty(), Optional.of(existingBelief));

        ReflectionTestUtils.invokeMethod(consolidator, "persistThought", user, sessionId,
                new ExtractorResult.ExtractedThought("자동적 사고", "self_blame", "core_self", "negative",
                        0.7, "나는 능력이 부족하다", "support"));

        verify(evidenceAccumulator).accumulate(
                eq(existingBelief), eq(BeliefEvidenceKind.SUPPORT), eq(sessionId), any());
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
    @DisplayName("핵심 요약 생성과 메타데이터 추출 지연을 서로 다른 단계로 기록한다")
    void consolidate_recordsSummaryGenerationAndMetadataExtractionStages() {
        SessionConsolidator consolidator = newConsolidator();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubCoreInputs(userId, sessionId);
        when(extractorLlmClient.extract("세션 요약", userId, sessionId)).thenReturn(ExtractorResult.empty());

        SessionConsolidator.EnrichmentInput result =
                consolidator.consolidate(sessionId, userId, "mio", 0);

        assertThat(result).isNotNull();
        assertThat(timerCount("summary_generation", "done")).isEqualTo(1);
        assertThat(timerCount("metadata_extraction", "done")).isEqualTo(1);
    }

    @Test
    @DisplayName("메타데이터 추출 실패는 요약 생성 성공과 분리해 계측한다")
    void consolidate_metadataExtractionFailure_recordsFailedStage() {
        SessionConsolidator consolidator = newConsolidator();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubCoreInputs(userId, sessionId);
        when(extractorLlmClient.extract("세션 요약", userId, sessionId))
                .thenThrow(new IllegalStateException("extractor down"));

        assertThatThrownBy(() -> consolidator.consolidate(sessionId, userId, "mio", 0))
                .isInstanceOf(IllegalStateException.class);

        assertThat(timerCount("summary_generation", "done")).isEqualTo(1);
        assertThat(timerCount("metadata_extraction", "failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("기존 요약 재처리는 모든 파생 상태와 이전 오류를 pending 기준으로 초기화한다")
    void consolidate_existingSummary_resetsDerivedComponentStates() {
        SessionConsolidator consolidator = newConsolidator();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubCoreInputs(userId, sessionId);
        when(extractorLlmClient.extract("세션 요약", userId, sessionId)).thenReturn(ExtractorResult.empty());
        when(sessionSummaryRepository.findBySession_Id(sessionId))
                .thenReturn(Optional.of(mock(com.mio.session.domain.SessionSummary.class)));

        consolidator.consolidate(sessionId, userId, "mio", 0);

        List<String> updateSql = mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("update"))
                .map(invocation -> invocation.getArgument(0).toString().replaceAll("\\s+", " "))
                .toList();
        assertThat(updateSql).anySatisfy(sql -> assertThat(sql)
                .contains("user_render_status = 'pending'")
                .contains("todo_status = 'pending'")
                .contains("user_render_pending_at = now()")
                .contains("todo_pending_at = now()")
                .contains("embedding_status = 'pending'")
                .contains("episode_emb = NULL")
                .contains("embedding_attempts = 0")
                .contains("embedding_claimed_at = NULL")
                .contains("component_errors = '{}'::jsonb")
                .contains("updated_at = now()"));
    }

    @Test
    @DisplayName("허용값 밖 polarity는 thought만 저장하고 신념 연결은 만들지 않는다")
    void persistThought_invalidPolarity_skipsBeliefConnection() {
        SessionConsolidator consolidator = newConsolidator();
        User user = userWithId();

        ReflectionTestUtils.invokeMethod(
                consolidator, "persistThought", user, UUID.randomUUID(), thought("core_self", "null"));

        verify(thoughtRepository).save(any());
        verifyNoInteractions(beliefRepository, evidenceAccumulator);
    }

    private long timerCount(String stage, String outcome) {
        var timer = meterRegistry.find("mio.summary.stage.duration")
                .tags("stage", stage, "outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private void stubCoreInputs(UUID userId, UUID sessionId) {
        Session session = mock(Session.class);
        User user = mock(User.class);
        when(session.getId()).thenReturn(sessionId);
        when(user.getId()).thenReturn(userId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(checkpointRepository.findBySession_IdOrderByCheckpointSeqAsc(sessionId)).thenReturn(List.of());
        // 사용자 발화 존재 게이트 (이슈 #530). 스텁이 없으면 null → false 로 읽혀 컨솔리데이션이
        // 시작 전에 종료되고, 이 픽스처를 쓰는 모든 시나리오가 "요약할 대화 없음" 이 된다.
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(sessionId))).thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), eq(sessionId))).thenReturn(List.of(Map.of(
                "role", "user",
                "content_ciphertext", new byte[]{1}
        )));
        when(messageEncryptor.decrypt(any())).thenReturn("대화 원문".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> chunks = invocation.getArgument(1, Consumer.class);
            chunks.accept("세션 요약");
            return new LlmStreamResult(1, LlmUsage.unresolved("gpt-4o-mini"), false);
        }).when(llmClient).stream(any(), any());
        when(sessionSummaryRepository.findBySession_Id(sessionId)).thenReturn(Optional.empty());
    }
}
