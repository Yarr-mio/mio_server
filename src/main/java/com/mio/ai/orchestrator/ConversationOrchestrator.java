package com.mio.ai.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisFlowService;
import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.memory.consolidation.ConversationCheckpointService;
import com.mio.ai.memory.ontology.OntologyInterventionFilter;
import com.mio.ai.memory.ontology.OntologyRelationExpander;
import com.mio.ai.memory.ontology.ReactiveOntologyActivator;
import com.mio.ai.memory.ontology.ReactiveOntologyActivationDispatcher;
import com.mio.ai.memory.ontology.ReactiveOntologyEligibility;
import com.mio.ai.input.InputNormalizer;
import com.mio.ai.input.SecurityRuleFilter;
import com.mio.ai.judge.InputJudge;
import com.mio.ai.judge.CbtInterventionState;
import com.mio.ai.judge.CbtMetadataClassifier;
import com.mio.ai.judge.CbtMetadataResult;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.OutputJudge;
import com.mio.ai.judge.OutputJudgeAction;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.memory.working.WorkingMessage;
import com.mio.ai.profile.ContextPreWarmer;
import com.mio.ai.profile.SafetyProfileBuilder.ProfileResult;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.moderation.OpenAiModerationClient;
import com.mio.ai.plan.ResponseContractResult;
import com.mio.ai.plan.ResponseContractValidator;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.plan.ResponsePlanner;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.policy.PolicyEngine;
import com.mio.ai.profile.SafetyProfile;
import com.mio.ai.profile.SafetyProfileBuilder;
import com.mio.ai.prompt.PromptBuilder;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1;
import com.mio.ai.safety.SafetyL1HistoryMessage;
import com.mio.ai.safety.SafetyL1Input;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.safety.SafetySignalCombiner;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.safety.UserMessageSignalAnalyzer;
import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.SecurityRefusalTemplate;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.MessageTurn;
import com.mio.session.domain.Session;
import com.mio.session.domain.TurnStatus;
import com.mio.session.dto.SseEventDto;
import com.mio.session.repository.SessionRepository;
import com.mio.session.service.CbtReconstructionService;
import com.mio.session.service.SessionMessagePersistenceService;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationOrchestrator {

    private static final String LLM_MODEL = "gpt-4o";
    // 출력 상한. 프롬프트가 "2-4문장"을 요구하고 실측 출력이 49~150 토큰이라 2.7배 여유다.
    // 상한이 없으면 폭주 응답 하나가 턴당 비용을 8.1배로 올린다 (기준선 문서 §5.7 R-1).
    private static final int LLM_MAX_COMPLETION_TOKENS = 400;
    private static final int EARLY_PREFILTER_THRESHOLD = 200;

    private final InputNormalizer inputNormalizer;
    private final SecurityRuleFilter securityRuleFilter;
    private final OpenAiModerationClient moderationClient;
    private final SafetyL1 safetyL1;
    private final SafetySignalCombiner signalCombiner;
    private final SafetyProfileBuilder safetyProfileBuilder;
    private final InputJudge inputJudge;
    private final ResponsePlanner responsePlanner;
    private final ResponseContractValidator responseContractValidator;
    private final CbtMetadataClassifier cbtMetadataClassifier;
    private final OutputPreFilter outputPreFilter;
    private final OutputJudge outputJudge;
    private final PolicyEngine policyEngine;
    private final OntologyInterventionFilter ontologyInterventionFilter;
    private final OntologyRelationExpander ontologyRelationExpander;
    private final ReactiveOntologyActivator reactiveOntologyActivator;
    private final ReactiveOntologyActivationDispatcher reactiveOntologyActivationDispatcher;
    private final ReactiveOntologyEligibility reactiveOntologyEligibility;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final CrisisFlowService crisisFlowService;
    private final SecurityRefusalTemplate securityRefusalTemplate;
    private final WorkingMemory workingMemory;
    private final ContextPreWarmer contextPreWarmer;
    private final AiDecisionLogger decisionLogger;
    private final CbtReconstructionService cbtReconstructionService;
    private final SessionMessagePersistenceService messagePersistenceService;
    private final ConversationCheckpointService checkpointService;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final UserMessageSignalAnalyzer userMessageSignalAnalyzer;
    private final ObjectMapper objectMapper;
    private final Executor outputJudgeExecutor;

    public void handle(UUID userId, UUID sessionId, String userMessage, SseEmitter emitter) {
        handle(userId, sessionId, userMessage, emitter, null);
    }

    public void handle(UUID userId, UUID sessionId, String userMessage, SseEmitter emitter,
                       String idempotencyKey) {
        long startMs = System.currentTimeMillis();

        // 이 턴이 어떻게 끝났는지. 어떤 경로로 빠져나가든 터미널 상태로 저장된다 (이슈 P0-A).
        AtomicReference<String> finishedReasonRef = new AtomicReference<>(null);
        // 위기 플로우로 끝난 턴의 severity. 재생 시 핫라인을 포함한 crisis 이벤트를 복원한다.
        AtomicReference<Integer> crisisSeverityRef = new AtomicReference<>(null);
        MessageTurn turn = null;
        // 결말이 이미 저장됐는지. done 을 보내기 전에 저장하는 것이 이 작업의 핵심 순서다.
        AtomicBoolean turnPersisted = new AtomicBoolean(false);
        String outboundMsgId = "msg_out_" + shortId();

        // CloudWatch 등 원시 로그를 session_id 로 교차 검색하기 위한 상관관계 키 (Sprint01, 이슈 #277).
        // TraceIdFilter 의 traceId 는 요청 단위라 세션 단위 상관관계를 주지 않는다.
        MDC.put("sessionId", sessionId.toString());

        try {
            Session session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

            String inboundMsgId = "msg_in_" + shortId();

            sendEvent(emitter, new SseEventDto.SessionMetaEvent(inboundMsgId, OffsetDateTime.now(ZoneOffset.UTC)));

            // 같은 Idempotency-Key 로 이미 완료된 턴이 있으면 저장된 응답을 재생한다.
            // LLM 을 다시 호출하지 않으므로 재시도가 비용을 늘리지 않는다 (이슈 P0-A).
            if (replayCompletedTurn(sessionId, idempotencyKey, outboundMsgId, emitter)) {
                emitter.complete();
                return;
            }

            // 1. Normalize
            String normalized = inputNormalizer.normalize(userMessage);
            UserMessageSignal userSignal = userMessageSignalAnalyzer.analyze(normalized);
            List<SafetyL1HistoryMessage> recentUserMessages =
                    messagePersistenceService.loadRecentUserSafetyHistory(sessionId, 3);

            // 사용자 발화를 생성 전에 저장하고 턴을 연다.
            // 반드시 위 히스토리 로드 뒤에 와야 한다 — 먼저 저장하면 현재 발화가 자기 자신의
            // "이전 3턴"에 섞여 감정 급락·반복 부정 판정이 오염된다.
            turn = messagePersistenceService.openTurn(
                    sessionId, userId, userMessage, userSignal, idempotencyKey);

            // 2. Load SafetyProfile (Redis cache HIT → JSON 역직렬화, MISS → buildSync)
            ProfileResult profileResult = safetyProfileBuilder.getWithCacheHit(sessionId.toString(), userId.toString());
            SafetyProfile profile = profileResult.profile();
            boolean safetyProfileCacheHit = profileResult.cacheHit();

            // 3. Safety checks (parallel in production; sequential with virtual threads)
            // 원문을 함께 넘긴다. 정규화본은 소문자화돼 있어 Base64 우회를 탐지할 수 없다.
            SecurityAssessment securityAssessment = securityRuleFilter.check(normalized, userMessage);
            ModerationResult moderation = moderationClient.moderate(normalized);
            SafetyL1Result l1Result = safetyL1.check(
                    new SafetyL1Input(
                            normalized,
                            recentUserMessages,
                            moderation,
                            profile,
                            userSignal.emotionScore(),
                            userSignal.biasType()));
            CombinedSignal combined = signalCombiner.combine(securityAssessment, l1Result, moderation, profile);

            // 안전한 결정론 신호만 같은 턴의 GRAPH_TRIGGER에 반영한다.
            if (reactiveOntologyEligibility.allowsTriggerActivation(userSignal, combined)) {
                reactiveOntologyActivator.activateVerifiedTriggers(sessionId, normalized, userSignal.biasType());
            }

            // 4. InputJudge (conditional)
            InputJudgeResult judgeResult = null;
            boolean inputJudgeCalled = false;
            if (inputJudge.shouldCallJudge(combined, profile)) {
                judgeResult = inputJudge.judge(normalized, combined, profile);
                inputJudgeCalled = true;
            }

            // 5. Working Memory (CBT counters) + Memory Context
            SessionDelta sessionDelta = workingMemory.getSessionDelta(sessionId);
            List<WorkingMessage> recentWorkingMessages = workingMemory.getRecentMessages(sessionId);
            recentWorkingMessages = recentWorkingMessages != null ? new ArrayList<>(recentWorkingMessages) : new ArrayList<>();
            String cachedMemory = contextPreWarmer.getCachedContext(sessionId);
            String liveMemory = contextPreWarmer.buildContextSync(
                    sessionId, userId, combined, profile, normalized, userSignal.biasType());
            boolean memoryCacheFallbackUsed = (liveMemory == null || liveMemory.isBlank())
                    && cachedMemory != null && !cachedMemory.isBlank();
            String memoryContext = memoryCacheFallbackUsed ? cachedMemory : liveMemory;
            String checkpointSummary = contextPreWarmer.getCachedCheckpoint(sessionId);

            // 6. Policy decision (10-step)
            PolicyDecision decision = policyEngine.decide(combined, judgeResult, profile, sessionDelta);
            decision = decision.withInterventionHints(
                    ontologyInterventionFilter.filter(decision.interventionHints(), combined, sessionDelta));
            decision = decision.withInterventionHints(
                    ontologyInterventionFilter.filter(
                            ontologyRelationExpander.rerankApprovedHints(
                                    decision.interventionHints(), userSignal.biasType()),
                            combined, sessionDelta));

            // 6b. 응답 계약 확정 (이슈 #303). 결정론적이며 LLM 을 호출하지 않는다.
            // 정책 결정을 바꾸지 않고 "무엇을 할지"만 덧붙인다 — 계획은 위험 등급을 낮출 수 없다.
            decision = decision.withResponsePlan(responsePlanner.plan(decision));
            ResponsePlan responsePlan = decision.responsePlan();

            // 현재 컨텍스트가 확정된 뒤, 안전한 생성 턴의 다음 턴 맥락만 비동기 활성화한다.
            if (reactiveOntologyEligibility.allowsBeliefActivation(userSignal, combined, decision)) {
                reactiveOntologyActivationDispatcher.activateBeliefs(userId, sessionId, normalized);
            }

            // 진행 중임을 알린다. 여기부터 LLM 생성·출력 검증이라 가장 오래 걸린다.
            // 이게 없으면 updated_at 이 턴을 연 시점에 고정되어 살아있는 턴이 버려진 것으로
            // 오판되고, 재시도가 같은 턴을 이어받는다.
            messagePersistenceService.touchTurn(turn.getId(), turn.getLeaseToken());

            // 7. Execute based on decision
            String assistantContent;
            long llmTtftMs = 0;
            // LLM 을 호출하지 않은 턴(보안 거절·위기·폴백)에서는 null 로 남는다.
            // 호출하지 않았다는 사실 자체가 기록돼야 하므로 빈 사용량으로 채우지 않는다.
            LlmUsage llmUsage = null;
            boolean crisisFlowTriggered = false;
            // 실제로 위기 플로우를 발동시킨 경로. PolicyDecision 이 아니라 실행 결과를 따라간다 —
            // 출력 가드가 승격시킨 위기는 decision.action() 이 GENERATE 라 결정에 경로가 없다.
            CrisisTrigger appliedCrisisTrigger = null;
            OutputPreFilterResult preFilterResult = OutputPreFilterResult.pass();
            OutputJudgeResult judgeActionResult = null;
            // 계약 검사 결과 (이슈 #303). 계획되지 않은 턴은 "통과"가 아니라 "대상 아님"이다.
            ResponseContractResult contractResult = ResponseContractResult.notApplicable();

            if (decision.action() == DecisionAction.SECURITY_REFUSAL) {
                assistantContent = securityRefusalTemplate.get();
                sendEvent(emitter, new SseEventDto.DeltaEvent(assistantContent, outboundMsgId));
                sendDoneEvent(emitter, finishedReasonRef, turn, crisisSeverityRef, turnPersisted, userId, sessionId, outboundMsgId, userSignal.emotionScore(), false,
                        userMessage, assistantContent, userSignal, sessionDelta, recentWorkingMessages,
                        "security_refusal", false);

            } else if (decision.action() == DecisionAction.CRISIS_FLOW) {
                crisisFlowTriggered = true;
                appliedCrisisTrigger = resolveCrisisTrigger(decision);
                // 위기 응답은 CrisisFlowService 가 crisis·done 을 직접 보낸다. 그 전에 결말을
                // 저장해야 한다 — 전송 뒤에 저장하면 커밋 전 프로세스 종료 시 사용자는 응답을
                // 받았는데 서버는 모르는 상태가 된다.
                CrisisFlowService.CrisisPreview preview =
                        crisisFlowService.preview(l1Result, appliedCrisisTrigger, userMessage);
                assistantContent = preview.fixedResponse();
                finishedReasonRef.set("crisis_flow");
                crisisSeverityRef.set(preview.severity());
                persistTurnOutcome(turn, turnPersisted, assistantContent, true,
                        "crisis_flow", preview.severity());

                CrisisFlowService.CrisisHandleResult crisisResult =
                        crisisFlowService.handle(l1Result, appliedCrisisTrigger, userMessage,
                                user, session, emitter, outboundMsgId, userSignal.emotionScore());
                recordCrisisOutcome(crisisResult, finishedReasonRef, crisisSeverityRef);

            } else if (decision.action() == DecisionAction.GENERATE) {
                // OutputGuard 실행 여부는 deliveryMode로 제어 (requireOutputGuard 필드는 감사 로그용)
                // GENERATE: build prompt with GenerationMode instruction
                String systemPrompt = promptBuilder.buildSystemPrompt(
                        decision.generationMode(), decision.interventionHints(), memoryContext,
                        session.getCharacterId(), checkpointSummary, responsePlan);
                List<WorkingMessage> historySlice = recentWorkingMessages.size() > 10
                        ? recentWorkingMessages.subList(recentWorkingMessages.size() - 10, recentWorkingMessages.size())
                        : recentWorkingMessages;
                LlmRequest llmRequest = LlmRequest.of(LLM_MODEL, systemPrompt, historySlice, userMessage)
                        .withMaxCompletionTokens(LLM_MAX_COMPLETION_TOKENS);
                StringBuilder contentBuilder = new StringBuilder();

                DeliveryMode deliveryMode = decision.deliveryMode();

                boolean inputHadRiskSignal = combined.riskCandidate() || combined.emotionSpike();

                if (deliveryMode == DeliveryMode.BUFFER) {
                    // Buffer: complete first, then OutputGuard, then SSE
                    LlmStreamResult streamResult =
                            llmClient.stream(llmRequest, withHeartbeat(turn, contentBuilder::append));
                    llmTtftMs = streamResult.ttftMs();
                    llmUsage = streamResult.usage();
                    assistantContent = contentBuilder.toString();

                    preFilterResult = outputPreFilter.checkWithCrisisContext(assistantContent, inputHadRiskSignal);
                    contractResult = responseContractValidator.validate(responsePlan, assistantContent);
                    OutputPreFilterResult bufferedGuardInput =
                            mergeContractViolations(preFilterResult, contractResult);
                    if (!bufferedGuardInput.passed()) {
                        judgeActionResult = outputJudge.judge(assistantContent, bufferedGuardInput);
                        if (judgeActionResult != null) {
                            assistantContent = resolveOutputJudgeAction(
                                    judgeActionResult, assistantContent, userMessage, l1Result, user, session, emitter,
                                    outboundMsgId, userSignal.emotionScore(),
                                    finishedReasonRef, crisisSeverityRef, turn, turnPersisted);
                            if (judgeActionResult.action() == OutputJudgeAction.CRISIS_FLOW) {
                                crisisFlowTriggered = true;
                                appliedCrisisTrigger = CrisisTrigger.OUTPUT_GUARD;
                            }
                        }
                    }
                    if (judgeActionResult == null || judgeActionResult.action() != OutputJudgeAction.CRISIS_FLOW) {
                        sendEvent(emitter, new SseEventDto.DeltaEvent(assistantContent, outboundMsgId));
                        sendDoneEvent(emitter, finishedReasonRef, turn, crisisSeverityRef, turnPersisted, userId, sessionId, outboundMsgId, userSignal.emotionScore(), false,
                                userMessage, assistantContent, userSignal, sessionDelta, recentWorkingMessages,
                                "stop", true);
                    }

                } else if (deliveryMode == DeliveryMode.CAUTIOUS_SPECULATIVE) {
                    // CAUTIOUS_SPECULATIVE: stream immediately + parallel OutputJudge
                    // Pre-filter runs every EARLY_PREFILTER_THRESHOLD chars during stream.
                    // If a violation is detected early, delta SSEs stop immediately and
                    // OutputJudge starts async on the partial snapshot.
                    AtomicBoolean stopSendingDeltas = new AtomicBoolean(false);
                    AtomicInteger lastCheckedLength = new AtomicInteger(0);
                    AtomicReference<OutputPreFilterResult> earlyFilterRef = new AtomicReference<>();
                    AtomicReference<CompletableFuture<OutputJudgeResult>> earlyJudgeFutureRef = new AtomicReference<>();
                    AtomicReference<String> capturedSnapshotRef = new AtomicReference<>();

                    LlmStreamResult streamResult = llmClient.stream(llmRequest, withHeartbeat(turn, chunk -> {
                        contentBuilder.append(chunk);
                        if (!stopSendingDeltas.get()) {
                            int currentLen = contentBuilder.length();
                            if (currentLen - lastCheckedLength.get() >= EARLY_PREFILTER_THRESHOLD) {
                                lastCheckedLength.set(currentLen);
                                String snapshot = contentBuilder.toString();
                                OutputPreFilterResult earlyCheck =
                                        outputPreFilter.checkWithCrisisContext(snapshot, inputHadRiskSignal);
                                if (!earlyCheck.passed()) {
                                    stopSendingDeltas.set(true);
                                    earlyFilterRef.set(earlyCheck);
                                    log.warn("OutputGuard early-stop during stream: session={} reasons={}",
                                            sessionId, earlyCheck.failReasons());
                                    capturedSnapshotRef.set(snapshot);
                                    final String capturedSnapshot = snapshot;
                                    final OutputPreFilterResult capturedCheck = earlyCheck;
                                    earlyJudgeFutureRef.set(CompletableFuture.supplyAsync(
                                            () -> outputJudge.judge(capturedSnapshot, capturedCheck), outputJudgeExecutor));
                                    return;
                                }
                            }
                            try {
                                sendEvent(emitter, new SseEventDto.DeltaEvent(chunk, outboundMsgId));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }));
                    llmTtftMs = streamResult.ttftMs();
                    llmUsage = streamResult.usage();

                    assistantContent = contentBuilder.toString();

                    CompletableFuture<OutputJudgeResult> judgeFuture = earlyJudgeFutureRef.get();
                    OutputPreFilterResult earlyFilter = earlyFilterRef.get();

                    if (earlyFilter != null) {
                        preFilterResult = earlyFilter;
                    }

                    if (judgeFuture == null) {
                        // No early stop — run post-stream pre-filter check
                        preFilterResult = outputPreFilter.checkWithCrisisContext(assistantContent, inputHadRiskSignal);
                        contractResult = responseContractValidator.validate(responsePlan, assistantContent);
                        // 계약 위반은 그 자체로 Judge 승격 사유다 (로드맵 §5.7). 이미 전달된
                        // 토큰을 되돌릴 수는 없지만, 위반한 응답을 검증 없이 종료하지는 않는다.
                        OutputPreFilterResult streamedGuardInput =
                                mergeContractViolations(preFilterResult, contractResult);
                        if (!streamedGuardInput.passed()) {
                            log.warn("OutputGuard post-stream: session={} reasons={}",
                                    sessionId, streamedGuardInput.failReasons());
                            final String fullContent = assistantContent;
                            final OutputPreFilterResult fullFilter = streamedGuardInput;
                            judgeFuture = CompletableFuture.supplyAsync(
                                    () -> outputJudge.judge(fullContent, fullFilter), outputJudgeExecutor);
                        }
                    }

                    if (judgeFuture != null) {
                        try {
                            judgeActionResult = judgeFuture.orTimeout(5, TimeUnit.SECONDS).join();
                        } catch (Exception e) {
                            log.warn("OutputJudge async failed, defaulting to REPLACE: {}", e.getMessage());
                            judgeActionResult = OutputJudgeResult.replace();
                        }
                        log.warn("OutputGuard action: session={} action={}", sessionId,
                                judgeActionResult.action());

                        boolean isCrisis = judgeActionResult.action() == OutputJudgeAction.CRISIS_FLOW;
                        if (isCrisis) {
                            crisisFlowTriggered = true;
                            appliedCrisisTrigger = CrisisTrigger.OUTPUT_GUARD;
                        }

                        if (isCrisis) {
                            // Bug 5 fix: invoke crisis flow — crisis + done SSE issued inside handle()
                            // 전송 전에 결말을 저장한다. handle() 이 done 까지 보내므로 그 뒤에
                            // 저장하면 사용자는 응답을 받았는데 서버는 모르는 창이 생긴다.
                            CrisisFlowService.CrisisPreview preview =
                                    crisisFlowService.preview(l1Result, CrisisTrigger.OUTPUT_GUARD, userMessage);
                            assistantContent = preview.fixedResponse();
                            finishedReasonRef.set("crisis_flow");
                            crisisSeverityRef.set(preview.severity());
                            persistTurnOutcome(turn, turnPersisted, assistantContent, true,
                                    "crisis_flow", preview.severity());

                            CrisisFlowService.CrisisHandleResult crisisResult =
                                    crisisFlowService.handle(l1Result, CrisisTrigger.OUTPUT_GUARD, userMessage,
                                            user, session, emitter, outboundMsgId, userSignal.emotionScore());
                            recordCrisisOutcome(crisisResult, finishedReasonRef, crisisSeverityRef);
                        } else {
                            String replacedContent = switch (judgeActionResult.action()) {
                                case REWRITE -> judgeActionResult.rewrittenContent() != null
                                        ? judgeActionResult.rewrittenContent()
                                        : "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";
                                case REPLACE -> "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";
                                case SEND, CRISIS_FLOW -> null;
                            };

                            if (replacedContent != null) {
                                assistantContent = replacedContent;
                                sendEvent(emitter, new SseEventDto.DeltaReplaceEvent(assistantContent, outboundMsgId));
                                sendDoneEvent(emitter, finishedReasonRef, turn, crisisSeverityRef, turnPersisted, userId, sessionId, outboundMsgId, userSignal.emotionScore(), false,
                                        userMessage, assistantContent, userSignal, sessionDelta, recentWorkingMessages,
                                        "replaced_by_guard", false);
                            } else if (stopSendingDeltas.get()) {
                                // Stopped mid-stream but content is safe — restore only the reviewed snapshot,
                                // not trailing tokens that arrived after the early stop
                                String reviewedContent = capturedSnapshotRef.get() != null
                                        ? capturedSnapshotRef.get() : assistantContent;
                                assistantContent = reviewedContent;
                                sendEvent(emitter, new SseEventDto.DeltaReplaceEvent(reviewedContent, outboundMsgId));
                                sendDoneEvent(emitter, finishedReasonRef, turn, crisisSeverityRef, turnPersisted, userId, sessionId, outboundMsgId, userSignal.emotionScore(), false,
                                        userMessage, reviewedContent, userSignal, sessionDelta, recentWorkingMessages,
                                        "stop", true);
                            } else {
                                sendDoneEvent(emitter, finishedReasonRef, turn, crisisSeverityRef, turnPersisted, userId, sessionId, outboundMsgId, userSignal.emotionScore(), false,
                                        userMessage, assistantContent, userSignal, sessionDelta, recentWorkingMessages,
                                        "stop", true);
                            }
                        }
                    } else {
                        sendDoneEvent(emitter, finishedReasonRef, turn, crisisSeverityRef, turnPersisted, userId, sessionId, outboundMsgId, userSignal.emotionScore(), false,
                                userMessage, assistantContent, userSignal, sessionDelta, recentWorkingMessages,
                                "stop", true);
                    }

                } else {
                    // SPECULATIVE: stream immediately, no post-guard
                    LlmStreamResult streamResult = llmClient.stream(llmRequest, withHeartbeat(turn, chunk -> {
                        contentBuilder.append(chunk);
                        try {
                            sendEvent(emitter, new SseEventDto.DeltaEvent(chunk, outboundMsgId));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }));
                    llmTtftMs = streamResult.ttftMs();
                    llmUsage = streamResult.usage();
                    assistantContent = contentBuilder.toString();
                    sendDoneEvent(emitter, finishedReasonRef, turn, crisisSeverityRef, turnPersisted, userId, sessionId, outboundMsgId, userSignal.emotionScore(), false,
                            userMessage, assistantContent, userSignal, sessionDelta, recentWorkingMessages,
                            "stop", true);
                }
            } else {
                // FALLBACK 또는 미지원 action — 안전 응답 반환
                log.warn("Unhandled decision action: {} for session={}", decision.action(), sessionId);
                assistantContent = "지금 연결에 문제가 생겼어요. 잠시 후 다시 시도해주세요.";
                sendEvent(emitter, new SseEventDto.DeltaEvent(assistantContent, outboundMsgId));
                sendDoneEvent(emitter, finishedReasonRef, turn, crisisSeverityRef, turnPersisted, userId, sessionId, outboundMsgId, userSignal.emotionScore(), false,
                        userMessage, assistantContent, userSignal, sessionDelta, recentWorkingMessages,
                        "error", false);
            }

            // 8. 안전망. 정상 경로는 done 을 보내기 전에 이미 저장했다(persistTurnOutcome).
            //    어떤 분기가 done 을 보내지 않고 여기까지 왔다면 그때 저장한다.
            persistTurnOutcome(turn, turnPersisted, assistantContent, crisisFlowTriggered,
                    resolveFinishedReason(finishedReasonRef), crisisSeverityRef.get());

            // 8b. 20개 메시지마다 비동기 체크포인트 생성 (non-blocking)
            checkpointService.maybeCheckpoint(sessionId, userId);

            // 9. Working Memory — 메시지 버퍼에 이번 턴 기록
            workingMemory.appendMessage(sessionId, "user", userMessage);
            workingMemory.appendMessage(sessionId, "assistant", assistantContent);

            // 10. Log decision
            long totalMs = System.currentTimeMillis() - startMs;
            decisionLogger.log(userId, sessionId, decision, moderation, l1Result,
                    securityAssessment, totalMs, llmTtftMs, crisisFlowTriggered,
                    inputJudgeCalled, preFilterResult, judgeActionResult,
                    profile.source(), safetyProfileCacheHit, memoryCacheFallbackUsed,
                    profile.degraded(), appliedCrisisTrigger, llmUsage, contractResult);

            emitter.complete();

        } catch (Exception e) {
            log.error("Conversation orchestration failed for session {}", sessionId, e);
            // 1) 결말을 먼저 저장한다. 이게 없으면 턴이 generating 에 영원히 머물러 재시도 시
            //    "진행 중"으로 오인된다 (이슈 P0-A). 연결 상태와 무관하게 항상 수행한다.
            if (turn != null) {
                messagePersistenceService.failTurn(
                        turn.getId(), turn.getLeaseToken(), resolveFinishedReason(finishedReasonRef));
            }
            // 2) 연결이 살아 있으면 결말을 알린다. 전송은 보장할 수 없다 — 이미 끊긴 뒤라면
            //    보낼 대상이 없다. 저장이 보장 대상이고 전송은 최선 노력이다.
            sendFallbackDone(emitter, outboundMsgId);
            emitter.completeWithError(e);
        } finally {
            MDC.remove("sessionId");
        }
    }

    /**
     * 위기 플로우의 결과를 턴에 반영한다.
     *
     * <p>위기 진입 경로가 셋이라(입력 판정 / CAUTIOUS_SPECULATIVE 출력 가드 / BUFFER 출력 가드)
     * 한 곳에서만 기록하면 나머지 경로의 턴이 사유 없이 {@code error} 로 확정된다. 정상 전달된
     * 위기가 실패로 기록되고, 재시도 시 그 잘못된 사유가 재생된다.
     *
     * <p>전송에 실패했다면 사용자는 핫라인을 보지 못했다 — 완료로 기록하지 않는다.
     */
    private void recordCrisisOutcome(CrisisFlowService.CrisisHandleResult result,
                                     AtomicReference<String> finishedReasonRef,
                                     AtomicReference<Integer> crisisSeverityRef) {
        if (result == null) {
            finishedReasonRef.set("error");
            return;
        }
        // 전달 실패 여부와 무관하게 위기로 끝난 턴이다. severity 를 반드시 남긴다 —
        // 이게 없으면 재생이 텍스트만 내보내고, 연결이 끊겨 재시도한 위기 사용자가 핫라인을
        // 보지 못한다. 그 상황을 막으려고 이 필드를 만들었는데 정반대로 동작했다.
        finishedReasonRef.set("crisis_flow");
        crisisSeverityRef.set(result.severity());

        if (!result.delivered()) {
            // 턴은 완료로 남긴다. 실패로 두면 재시도가 파이프라인을 다시 태워 crisis_events 에
            // 중복 행이 생긴다. 완료로 두면 재시도가 저장된 위기 응답을 핫라인과 함께 재생한다.
            log.warn("Crisis turn completed but not delivered — retry will replay the hotline");
        }
    }

    /**
     * 스트리밍 중 턴이 살아 있음을 알리는 소비자로 감싼다.
     *
     * <p>보장 범위는 청크가 들어오는 동안이다 — 자세한 내용은 {@link TurnHeartbeat} 참조.
     */
    private Consumer<String> withHeartbeat(MessageTurn turn, Consumer<String> delegate) {
        if (turn == null) {
            return delegate;
        }
        return new TurnHeartbeat(
                System::currentTimeMillis,
                () -> messagePersistenceService.touchTurn(turn.getId(), turn.getLeaseToken())
        ).wrap(delegate);
    }

    /**
     * 턴의 결말을 저장한다. 한 턴에서 한 번만 수행된다.
     *
     * <p>저장 실패는 삼키지 않는다 — 결말이 남지 않으면 재시도가 이미 보낸 응답을 다시
     * 생성하므로, 이 작업의 목적 자체가 무너진다. 예외는 상위 catch 로 올라가 failTurn 과
     * 폴백 전송으로 이어진다.
     */
    private void persistTurnOutcome(MessageTurn turn, AtomicBoolean turnPersisted,
                                    String assistantContent, boolean crisisFlowTriggered,
                                    String finishedReason, Integer crisisSeverity) {
        if (turn == null || !turnPersisted.compareAndSet(false, true)) {
            return;
        }
        messagePersistenceService.completeTurn(turn.getId(), turn.getLeaseToken(),
                assistantContent, crisisFlowTriggered, finishedReason, crisisSeverity);
    }

    /** 실패로 끝난 턴에서 사용자에게 내보내는 문구. */
    private static final String FAILURE_FALLBACK =
            "지금 답변을 만들지 못했어요. 잠시 후 다시 말씀해주시겠어요?";

    /**
     * 실패한 턴의 결말을 클라이언트에 알린다.
     *
     * <p>{@code delta.replace} 를 쓰는 이유는 이미 일부 토큰이 나갔을 수 있어서다. 그대로 두면
     * 문장이 중간에 끊긴 채 남는다. 아무것도 안 나갔다면 이 문구가 그대로 보인다.
     *
     * <p>여기서 나는 예외는 삼킨다. 이 메서드는 이미 실패 처리 중에 호출되고, 연결이 끊겨
     * 전송이 불가능한 것이 가장 흔한 실패 원인이다. 그건 정상이지 새로운 오류가 아니다.
     */
    private void sendFallbackDone(SseEmitter emitter, String outboundMsgId) {
        try {
            sendEvent(emitter, new SseEventDto.DeltaReplaceEvent(FAILURE_FALLBACK, outboundMsgId));
            sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId, null, false, false, "error"));
        } catch (Exception ignored) {
            log.debug("Fallback done not delivered — connection already closed: msgId={}", outboundMsgId);
        }
    }

    /**
     * 이미 완료된 턴이면 저장된 응답을 재생하고 {@code true} 를 반환한다.
     *
     * <p>클라이언트가 응답을 받는 도중 연결이 끊겨 같은 키로 다시 요청한 경우다. 파이프라인을
     * 다시 태우면 LLM 을 또 호출하고(비용) 같은 사용자 발화로 다른 답을 주게 된다. 저장된 것을
     * 그대로 내보내는 것이 idempotency 의 의미에 맞는다.
     *
     * <p>재생 실패는 삼킨다 — 재생하지 못하면 새로 생성하는 편이 낫지, 요청 전체를 실패시킬
     * 이유가 없다.
     */
    private boolean replayCompletedTurn(UUID sessionId, String idempotencyKey,
                                        String outboundMsgId, SseEmitter emitter) {
        if (idempotencyKey == null) {
            return false;
        }
        try {
            MessageTurn completed = messagePersistenceService.findTurn(sessionId, idempotencyKey)
                    .filter(t -> t.getStatus() == TurnStatus.COMPLETED)
                    .orElse(null);
            if (completed == null) {
                return false;
            }

            String content = messagePersistenceService
                    .loadAssistantContent(completed.getAssistantMessageId())
                    .orElse(null);
            if (content == null) {
                // 재생할 원문이 없으면 새로 처리하게 둔다.
                //
                // 위기 턴은 여기 걸리면 안 된다 — 고정 응답도 assistant 메시지로 저장되므로
                // 원문이 있어야 정상이다. 없다는 건 데이터가 어긋났다는 뜻이고, 그대로 재생하면
                // 핫라인 없는 빈 응답이 나가므로 재생하지 않고 로그를 남긴다.
                if (completed.getCrisisSeverity() != null) {
                    log.error("Crisis turn has no assistant content — cannot replay hotline: turnId={}",
                            completed.getId());
                }
                return false;
            }

            log.info("Replaying completed turn: turnId={} reason={} crisisSeverity={}",
                    completed.getId(), completed.getFinishedReason(), completed.getCrisisSeverity());

            Integer severity = completed.getCrisisSeverity();
            if (severity != null) {
                // 위기로 끝난 턴이다. 텍스트만 재생하면 사용자가 핫라인을 다시 보지 못한다 —
                // 연결이 끊겨 재시도하는 위기 사용자에게 가장 필요한 것이 그 번호다.
                sendEvent(emitter, crisisFlowService.buildCrisisEvent(severity, content));
                sendEvent(emitter, new SseEventDto.DoneEvent(
                        outboundMsgId, null, true, false, completed.getFinishedReason()));
                return true;
            }

            sendEvent(emitter, new SseEventDto.DeltaEvent(content, outboundMsgId));
            sendEvent(emitter, new SseEventDto.DoneEvent(
                    outboundMsgId, null, false, false, completed.getFinishedReason()));
            return true;
        } catch (Exception e) {
            log.warn("Turn replay failed, falling through to normal processing: key={}", idempotencyKey, e);
            return false;
        }
    }

    /**
     * 턴의 터미널 사유를 정한다.
     *
     * <p>{@code done} 을 내보내지 못하고 끝난 경로에서는 사유가 비어 있다. 그 경우 {@code error}
     * 로 남긴다 — DB CHECK 제약상 터미널 상태에는 사유가 반드시 있어야 하고, 실제로도
     * "결말을 알리지 못하고 끝났다"가 맞는 서술이다.
     */
    private String resolveFinishedReason(AtomicReference<String> finishedReasonRef) {
        String reason = finishedReasonRef.get();
        return reason != null ? reason : "error";
    }

    /**
     * PolicyEngine 이 실어 보낸 위기 진입 경로를 꺼낸다.
     *
     * <p>{@code null} 은 {@code CRISIS_FLOW} 결정에 경로가 빠졌다는 뜻이라 정상 상태가 아니다.
     * 여기서 예외를 던지면 위기 사용자의 응답이 통째로 실패하므로, 가장 흔한 경로인
     * {@code L1_KEYWORD} 로 이어가되 로그를 남긴다.
     */
    /**
     * 사전 필터 결과와 계약 검사 결과를 하나의 가드 입력으로 합친다 (이슈 #303).
     *
     * <p>둘 중 하나라도 걸리면 OutputJudge 를 부른다. 계약 위반 사유도 함께 넘겨야 Judge 가
     * 무엇이 문제인지 알고 판단한다. 로그에는 두 결과를 따로 남긴다 — 합쳐서 기록하면
     * 의미 판단 실패와 계약 위반의 비율을 나눌 수 없다.
     */
    private OutputPreFilterResult mergeContractViolations(
            OutputPreFilterResult preFilterResult, ResponseContractResult contractResult) {
        if (contractResult == null || contractResult.passed()) {
            return preFilterResult;
        }
        List<String> reasons = new ArrayList<>(preFilterResult.failReasons());
        contractResult.violations().forEach(violation -> reasons.add("contract:" + violation));
        return OutputPreFilterResult.fail(reasons);
    }

    private CrisisTrigger resolveCrisisTrigger(PolicyDecision decision) {
        if (decision.crisisTrigger() != null) {
            return decision.crisisTrigger();
        }
        log.warn("CRISIS_FLOW decision without crisisTrigger: decisionId={}", decision.decisionId());
        return CrisisTrigger.L1_KEYWORD;
    }

    private String resolveOutputJudgeAction(
            OutputJudgeResult result,
            String originalContent,
            String originalUserMessage,
            SafetyL1Result l1Result,
            User user,
            Session session,
            SseEmitter emitter,
            String outboundMsgId,
            Integer emotionScore,
            AtomicReference<String> finishedReasonRef,
            AtomicReference<Integer> crisisSeverityRef,
            MessageTurn turn,
            AtomicBoolean turnPersisted) throws IOException {

        return switch (result.action()) {
            case SEND -> originalContent;
            case REWRITE -> result.rewrittenContent() != null ? result.rewrittenContent() : originalContent;
            case REPLACE -> "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";
            case CRISIS_FLOW -> {
                // 전송 전에 결말을 저장한다 (이슈 P0-A 리뷰 반영).
                CrisisFlowService.CrisisPreview preview =
                        crisisFlowService.preview(l1Result, CrisisTrigger.OUTPUT_GUARD, originalUserMessage);
                persistTurnOutcome(turn, turnPersisted, preview.fixedResponse(), true,
                        "crisis_flow", preview.severity());

                CrisisFlowService.CrisisHandleResult cr =
                        crisisFlowService.handle(l1Result, CrisisTrigger.OUTPUT_GUARD, originalUserMessage,
                                user, session, emitter, outboundMsgId, emotionScore);
                recordCrisisOutcome(cr, finishedReasonRef, crisisSeverityRef);
                yield cr != null ? cr.fixedResponse() : "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";
            }
        };
    }

    private void sendDoneEvent(
            SseEmitter emitter,
            AtomicReference<String> finishedReasonRef,
            MessageTurn turn,
            AtomicReference<Integer> crisisSeverityRef,
            AtomicBoolean turnPersisted,
            UUID userId,
            UUID sessionId,
            String outboundMsgId,
            Integer emotionScore,
            boolean isCrisisFlagged,
            String userMessage,
            String assistantContent,
            UserMessageSignal userSignal,
            SessionDelta sessionDelta,
            List<WorkingMessage> recentWorkingMessages,
            String finishedReason,
            boolean classifyCbt) throws IOException {

        finishedReasonRef.set(finishedReason);

        // 결말을 먼저 저장하고 그 다음에 done 을 보낸다.
        // 순서가 반대면, done 이 클라이언트에 도착한 뒤 커밋 전에 프로세스가 죽었을 때 DB 에는
        // generating 턴과 사용자 발화만 남는다. 재시도는 사용자가 이미 받은 응답을 다시 생성한다.
        persistTurnOutcome(turn, turnPersisted, assistantContent, isCrisisFlagged,
                finishedReason, crisisSeverityRef.get());

        CbtMetadataResult metadata = classifyCbt
                ? cbtMetadataClassifier.classify(
                        sessionDelta.cbtInterventionState(),
                        recentWorkingMessages,
                        userMessage,
                        assistantContent,
                        userSignal,
                        sessionDelta.socraticQuestionsUsed(),
                        isCrisisFlagged)
                : CbtMetadataResult.none();

        UUID emotionScoreTargetId = null;
        if (metadata.shouldCreateEmotionScoreTarget()) {
            try {
                emotionScoreTargetId = cbtReconstructionService.createEmotionScoreTarget(
                        userId,
                        sessionId,
                        userMessage,
                        assistantContent,
                        metadata,
                        emotionScore
                ).getId();
            } catch (Exception e) {
                log.warn("Failed to create CBT emotion-score target for sessionId={} — continuing without target",
                        sessionId, e);
            }
        }

        if (classifyCbt) {
            workingMemory.updateCbtInterventionState(sessionId, metadata.state().wireValue());
            if (metadata.state() == CbtInterventionState.SOCRATIC_ASKED) {
                workingMemory.incrementSocraticQuestionCount(sessionId);
            }
        }

        boolean isSocratic = metadata.socratic() || metadata.state() == CbtInterventionState.SOCRATIC_ASKED;

        sendEvent(emitter, new SseEventDto.DoneEvent(
                outboundMsgId,
                emotionScore,
                isCrisisFlagged,
                isSocratic,
                metadata.state().wireValue(),
                metadata.completionReason(),
                emotionScoreTargetId != null,
                emotionScoreTargetId,
                emotionScoreTargetId != null ? "after" : null,
                finishedReason
        ));
    }

    private void sendEvent(SseEmitter emitter, SseEventDto event) throws IOException {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.eventName())
                    .data(objectMapper.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
