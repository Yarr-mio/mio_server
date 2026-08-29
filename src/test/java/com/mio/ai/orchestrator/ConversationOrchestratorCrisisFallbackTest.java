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
import com.mio.ai.support.RecordingSseEmitter;
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
 * <p><b>단정은 필드를 본다 (이슈 #499).</b> 이전에는 손으로 만든 emitter 가 SSE 프레임 전체를
 * 한 문자열로 이어붙이고({@code event:}·{@code data:} 표지와 DTO 의 {@code toString()} 이 뒤섞인다)
 * 테스트가 그 덩어리에 부분일치했다. 세 자리 전화번호 {@code 109} 는 페이로드의 다른 숫자와
 * 겹쳐 양방향으로 깨졌다 — {@code doesNotContain} 은 CI 에서 무작위로 실패했고,
 * {@code contains} 는 핫라인이 빠져도 통과할 수 있었다. 후자가 더 나쁘다: 안전 회귀를 통과시킨다.
 *
 * <p>그래서 {@link RecordingSseEmitter} 로 모은다 — 프레임을 파싱해 이벤트 이름과 JSON 페이로드로
 * 나누므로, 단정이 {@code resources.hotlines[].number} 같은 <b>필드</b>를 향한다. 숫자열과의
 * 우연한 겹침이 구조적으로 불가능해진다.
 *
 * <p>이전에는 최상위 catch 가 항상 "지금 답변을 만들지 못했어요..." 만 보냈다. 활성 위기
 * triage 도중 어떤 예외가 나면, 방금 자·타해 의도를 확인하던 사용자에게 핫라인 없는
 * 재시도 문구가 나갔다 — 그 순간 가장 필요한 것이 상담 전화번호다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationOrchestratorCrisisFallbackTest {

    /** 하이픈이 없어 수치 id 와 구별되지 않던 번호. 이제 필드로 비교하므로 안전하다. */
    private static final String SUICIDE_HOTLINE = "109";
    private static final String MENTAL_HEALTH_HOTLINE = "1577-0199";

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
                        CrisisFlowStage.PLAN, CrisisFlowStatus.ACTIVE, "advanced", 3));
        doThrow(new RuntimeException("db down mid-crisis"))
                .when(messagePersistenceService)
                .completeTurn(any(), any(), anyString(), anyBoolean(), anyString(), any());

        RecordingSseEmitter emitter = new RecordingSseEmitter(new ObjectMapper());
        orchestrator.handle(userId, sessionId, "네", emitter, null);

        assertThat(emitter.crisisHotlineNumbers())
                .as("위기 triage 중 실패한 턴의 폴백에는 핫라인이 있어야 한다")
                .contains(SUICIDE_HOTLINE, MENTAL_HEALTH_HOTLINE);
    }

    @Test
    @DisplayName("위기 라우팅 자체가 예외로 죽어도 폴백에 핫라인이 포함된다")
    void routeFailureFallsBackWithHotlines() {
        doThrow(new IllegalStateException("metrics registry failure"))
                .when(crisisFixedFlowCoordinator).route(sessionId, userId, "네");

        RecordingSseEmitter emitter = new RecordingSseEmitter(new ObjectMapper());
        orchestrator.handle(userId, sessionId, "네", emitter, null);

        assertThat(emitter.crisisHotlineNumbers())
                .contains(SUICIDE_HOTLINE, MENTAL_HEALTH_HOTLINE);
    }

    @Test
    @DisplayName("라우팅에 닿기 전에 턴 열기가 실패해도 활성 triage 면 핫라인이 포함된다")
    void preRouteFailureDuringActiveFlowFallsBackWithHotlines() {
        // route() 는 사용자 발화 저장 뒤에야 호출된다. 그 앞에서 죽으면 위기 맥락 표시가
        // 붙을 기회조차 없었다 — 직전 턴에서 "죽고 싶은 생각이 있나요?" 에 답하려던
        // 사용자가 핫라인 없는 재시도 문구를 받는 경로다.
        when(crisisFixedFlowCoordinator.hasActiveFlow(sessionId)).thenReturn(true);
        doThrow(new RuntimeException("db down before routing"))
                .when(messagePersistenceService).openTurn(any(), any(), any(), any(), any());

        RecordingSseEmitter emitter = new RecordingSseEmitter(new ObjectMapper());
        orchestrator.handle(userId, sessionId, "네", emitter, null);

        assertThat(emitter.crisisHotlineNumbers())
                .as("라우팅 전 실패라도 활성 triage 면 핫라인이 있어야 한다")
                .contains(SUICIDE_HOTLINE, MENTAL_HEALTH_HOTLINE);
    }

    @Test
    @DisplayName("세션·유저 조회가 죽어도 활성 triage 면 핫라인이 포함된다")
    void sessionLookupFailureDuringActiveFlowFallsBackWithHotlines() {
        // triage 조회는 sessionId 하나만 필요하므로 세션·유저 조회보다 앞에 있어야 한다.
        // 뒤에 있으면 이 두 조회가 DB 장애로 죽는 순간 위기 맥락 표시가 붙을 기회가 없고,
        // fail-closed 장치가 호출될 기회조차 얻지 못한다.
        when(crisisFixedFlowCoordinator.hasActiveFlow(sessionId)).thenReturn(true);
        when(sessionRepository.findById(sessionId))
                .thenThrow(new RuntimeException("db down before session load"));

        RecordingSseEmitter emitter = new RecordingSseEmitter(new ObjectMapper());
        orchestrator.handle(userId, sessionId, "네", emitter, null);

        assertThat(emitter.crisisHotlineNumbers())
                .as("세션 조회 실패라도 활성 triage 면 핫라인이 있어야 한다")
                .contains(SUICIDE_HOTLINE, MENTAL_HEALTH_HOTLINE);
    }

    @Test
    @DisplayName("활성 여부를 조회하지 못하면 핫라인을 포함하는 쪽으로 닫는다")
    void probeFailureFallsBackWithHotlines() {
        // 조회 자체가 실패하면 triage 중인지 알 수 없다. 진행 중인 triage 를 놓치는 쪽이
        // 위기가 아닌 사용자에게 핫라인을 한 번 더 보여주는 쪽보다 나쁜 실패다.
        when(crisisFixedFlowCoordinator.hasActiveFlow(sessionId)).thenReturn(true);
        doThrow(new RuntimeException("history load failed"))
                .when(messagePersistenceService).loadRecentUserSafetyHistory(any(), anyInt());

        RecordingSseEmitter emitter = new RecordingSseEmitter(new ObjectMapper());
        orchestrator.handle(userId, sessionId, "네", emitter, null);

        assertThat(emitter.crisisHotlineNumbers())
                .contains(SUICIDE_HOTLINE, MENTAL_HEALTH_HOTLINE);
    }

    @Test
    @DisplayName("위기 맥락이 아닌 실패는 기존 재시도 문구를 유지한다")
    void nonCrisisFailureKeepsGenericFallback() {
        when(crisisFixedFlowCoordinator.route(sessionId, userId, "네"))
                .thenReturn(CrisisFixedRoute.notRouted());
        when(safetyProfileBuilder.getWithCacheHit(anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        RecordingSseEmitter emitter = new RecordingSseEmitter(new ObjectMapper());
        orchestrator.handle(userId, sessionId, "네", emitter, null);

        assertThat(emitter.messageBodyText())
                .as("위기가 아닌 실패는 기존 재시도 문구를 그대로 쓴다")
                .contains("지금 답변을 만들지 못했어요");
        assertThat(emitter.eventNames())
                .as("위기 이벤트 자체가 나가지 않아야 한다 — 문구만 보면 핫라인 블록의 유무를 놓친다")
                .doesNotContain("crisis");
        assertThat(emitter.crisisHotlineNumbers()).isEmpty();
    }

}
