package com.mio.ai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisEventRecorder;
import com.mio.ai.crisis.CrisisFixedFlowCoordinator;
import com.mio.ai.crisis.CrisisFixedRoute;
import com.mio.ai.crisis.CrisisFlowService;
import com.mio.ai.crisis.CrisisFlowStage;
import com.mio.ai.crisis.CrisisFlowStateMachine;
import com.mio.ai.crisis.CrisisFlowStatus;
import com.mio.ai.input.InputNormalizer;
import com.mio.ai.input.SecurityRuleFilter;
import com.mio.ai.judge.CbtMetadataClassifier;
import com.mio.ai.judge.InputJudge;
import com.mio.ai.judge.OutputJudge;
import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.memory.consolidation.ConversationCheckpointService;
import com.mio.ai.memory.ontology.OntologyInterventionFilter;
import com.mio.ai.memory.ontology.OntologyRelationExpander;
import com.mio.ai.memory.ontology.ReactiveOntologyActivationDispatcher;
import com.mio.ai.memory.ontology.ReactiveOntologyActivator;
import com.mio.ai.memory.ontology.ReactiveOntologyEligibility;
import com.mio.ai.moderation.OpenAiModerationClient;
import com.mio.ai.plan.ResponseContractValidator;
import com.mio.ai.plan.ResponsePlanner;
import com.mio.ai.policy.PolicyEngine;
import com.mio.ai.profile.ContextPreWarmer;
import com.mio.ai.profile.SafetyProfileBuilder;
import com.mio.ai.prompt.PromptBuilder;
import com.mio.ai.safety.SafetyL1;
import com.mio.ai.safety.SafetySignalCombiner;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.safety.UserMessageSignalAnalyzer;
import com.mio.ai.security.SecurityRefusalTemplate;
import com.mio.session.domain.MessageTurn;
import com.mio.session.domain.Session;
import com.mio.session.repository.SessionRepository;
import com.mio.session.service.CbtReconstructionService;
import com.mio.session.service.SessionMessagePersistenceService;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 위기 맥락에서 실패한 턴의 폴백은 핫라인을 포함해야 한다 (PR #452 리뷰 HIGH).
 *
 * <p>이전에는 최상위 catch 가 항상 "지금 답변을 만들지 못했어요..." 만 보냈다. 활성 위기
 * triage 도중 어떤 예외가 나면, 방금 자·타해 의도를 확인하던 사용자에게 핫라인 없는
 * 재시도 문구가 나갔다 — 그 순간 가장 필요한 것이 상담 전화번호다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationOrchestratorCrisisFallbackTest {

    @Mock private InputNormalizer inputNormalizer;
    @Mock private SecurityRuleFilter securityRuleFilter;
    @Mock private OpenAiModerationClient moderationClient;
    @Mock private SafetyL1 safetyL1;
    @Mock private SafetySignalCombiner signalCombiner;
    @Mock private SafetyProfileBuilder safetyProfileBuilder;
    @Mock private InputJudge inputJudge;
    @Mock private ResponsePlanner responsePlanner;
    @Mock private ResponseContractValidator responseContractValidator;
    @Mock private CbtMetadataClassifier cbtMetadataClassifier;
    @Mock private OutputPreFilter outputPreFilter;
    @Mock private OutputJudge outputJudge;
    @Mock private PolicyEngine policyEngine;
    @Mock private OntologyInterventionFilter ontologyInterventionFilter;
    @Mock private OntologyRelationExpander ontologyRelationExpander;
    @Mock private ReactiveOntologyActivator reactiveOntologyActivator;
    @Mock private ReactiveOntologyActivationDispatcher reactiveOntologyActivationDispatcher;
    @Mock private ReactiveOntologyEligibility reactiveOntologyEligibility;
    @Mock private PromptBuilder promptBuilder;
    @Mock private LlmClient llmClient;
    @Mock private CrisisFlowService crisisFlowService;
    @Mock private CrisisFixedFlowCoordinator crisisFixedFlowCoordinator;
    @Mock private SecurityRefusalTemplate securityRefusalTemplate;
    @Mock private com.mio.ai.memory.working.WorkingMemory workingMemory;
    @Mock private ContextPreWarmer contextPreWarmer;
    @Mock private AiDecisionLogger decisionLogger;
    @Mock private AiTurnMetrics aiTurnMetrics;
    @Mock private CbtReconstructionService cbtReconstructionService;
    @Mock private SessionMessagePersistenceService messagePersistenceService;
    @Mock private ConversationCheckpointService checkpointService;
    @Mock private SessionRepository sessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserMessageSignalAnalyzer userMessageSignalAnalyzer;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Mock private Executor outputJudgeExecutor;

    @InjectMocks private ConversationOrchestrator orchestrator;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final CrisisFlowStateMachine stateMachine = new CrisisFlowStateMachine();

    @BeforeEach
    void setUp() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(mock(Session.class)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(mock(User.class)));
        when(inputNormalizer.normalize(anyString())).thenReturn("네");
        when(userMessageSignalAnalyzer.analyze(any())).thenReturn(new UserMessageSignal(20, null));
        when(messagePersistenceService.loadRecentUserSafetyHistory(any(), anyInt()))
                .thenReturn(List.of());
        MessageTurn turn = mock(MessageTurn.class);
        when(turn.getId()).thenReturn(UUID.randomUUID());
        when(turn.getLeaseToken()).thenReturn(UUID.randomUUID());
        when(messagePersistenceService.openTurn(any(), any(), any(), any(), any()))
                .thenReturn(turn);

        // 폴백 조립이 쓰는 위기 고정 문구·이벤트는 실제 구현을 그대로 위임한다.
        when(crisisFixedFlowCoordinator.handoffResponse())
                .thenReturn(stateMachine.handoffResponse());
        CrisisFlowService realCrisisFlowService =
                new CrisisFlowService(mock(CrisisEventRecorder.class));
        when(crisisFlowService.buildCrisisEvent(anyInt(), anyString()))
                .thenAnswer(inv -> realCrisisFlowService.buildCrisisEvent(
                        inv.getArgument(0), inv.getArgument(1)));
    }

    @Test
    @DisplayName("위기 고정 플로우 턴이 도중에 실패하면 폴백에 핫라인이 포함된다")
    void crisisTurnFailureFallsBackWithHotlines() {
        when(crisisFixedFlowCoordinator.route(sessionId, userId, "네"))
                .thenReturn(CrisisFixedRoute.routed(
                        stateMachine.initialResponse(),
                        CrisisFlowStage.PLAN, CrisisFlowStatus.ACTIVE, "advanced"));
        doThrow(new RuntimeException("db down mid-crisis"))
                .when(messagePersistenceService)
                .completeTurn(any(), any(), anyString(), anyBoolean(), anyString(), any());

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.handle(userId, sessionId, "네", emitter, null);

        assertThat(emitter.payload())
                .as("위기 triage 중 실패한 턴의 폴백에는 핫라인이 있어야 한다")
                .contains("109")
                .contains("1577-0199");
    }

    @Test
    @DisplayName("위기 라우팅 자체가 예외로 죽어도 폴백에 핫라인이 포함된다")
    void routeFailureFallsBackWithHotlines() {
        doThrow(new IllegalStateException("metrics registry failure"))
                .when(crisisFixedFlowCoordinator).route(sessionId, userId, "네");

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.handle(userId, sessionId, "네", emitter, null);

        assertThat(emitter.payload()).contains("109").contains("1577-0199");
    }

    @Test
    @DisplayName("위기 맥락이 아닌 실패는 기존 재시도 문구를 유지한다")
    void nonCrisisFailureKeepsGenericFallback() {
        when(crisisFixedFlowCoordinator.route(sessionId, userId, "네"))
                .thenReturn(CrisisFixedRoute.notRouted());
        when(safetyProfileBuilder.getWithCacheHit(anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.handle(userId, sessionId, "네", emitter, null);

        assertThat(emitter.payload())
                .contains("지금 답변을 만들지 못했어요")
                .doesNotContain("109");
    }

    /** SSE 로 직렬화되어 나간 payload 를 문자열로 모은다. 완료·오류 콜백은 무시한다. */
    private static final class CapturingEmitter extends SseEmitter {
        private final StringBuilder sent = new StringBuilder();

        private CapturingEmitter() {
            super(30_000L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) {
            builder.build().forEach(data -> sent.append(data.getData()));
        }

        @Override
        public synchronized void complete() {
            // no-op: 테스트에서는 실제 비동기 응답이 없다.
        }

        @Override
        public synchronized void completeWithError(Throwable ex) {
            // no-op
        }

        private String payload() {
            return sent.toString();
        }
    }
}
