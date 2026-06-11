package com.mio.ai.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisFlowService;
import com.mio.ai.memory.consolidation.ConversationCheckpointService;
import com.mio.ai.input.InputNormalizer;
import com.mio.ai.input.SecurityRuleFilter;
import com.mio.ai.judge.InputJudge;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.OutputJudge;
import com.mio.ai.judge.OutputJudgeAction;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.profile.ContextPreWarmer;
import com.mio.ai.profile.SafetyProfileBuilder.ProfileResult;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.moderation.OpenAiModerationClient;
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
import com.mio.session.domain.Session;
import com.mio.session.dto.SseEventDto;
import com.mio.session.repository.SessionRepository;
import com.mio.session.service.SessionMessagePersistenceService;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
    private static final int EARLY_PREFILTER_THRESHOLD = 200;

    private final InputNormalizer inputNormalizer;
    private final SecurityRuleFilter securityRuleFilter;
    private final OpenAiModerationClient moderationClient;
    private final SafetyL1 safetyL1;
    private final SafetySignalCombiner signalCombiner;
    private final SafetyProfileBuilder safetyProfileBuilder;
    private final InputJudge inputJudge;
    private final OutputPreFilter outputPreFilter;
    private final OutputJudge outputJudge;
    private final PolicyEngine policyEngine;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final CrisisFlowService crisisFlowService;
    private final SecurityRefusalTemplate securityRefusalTemplate;
    private final WorkingMemory workingMemory;
    private final ContextPreWarmer contextPreWarmer;
    private final AiDecisionLogger decisionLogger;
    private final SessionMessagePersistenceService messagePersistenceService;
    private final ConversationCheckpointService checkpointService;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final UserMessageSignalAnalyzer userMessageSignalAnalyzer;
    private final ObjectMapper objectMapper;
    private final Executor outputJudgeExecutor;

    public void handle(UUID userId, UUID sessionId, String userMessage, SseEmitter emitter) {
        long startMs = System.currentTimeMillis();

        try {
            Session session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

            String inboundMsgId = "msg_in_" + shortId();
            String outboundMsgId = "msg_out_" + shortId();

            sendEvent(emitter, new SseEventDto.SessionMetaEvent(inboundMsgId, OffsetDateTime.now(ZoneOffset.UTC)));

            // 1. Normalize
            String normalized = inputNormalizer.normalize(userMessage);
            UserMessageSignal userSignal = userMessageSignalAnalyzer.analyze(normalized);
            List<SafetyL1HistoryMessage> recentUserMessages =
                    messagePersistenceService.loadRecentUserSafetyHistory(sessionId, 3);

            // 2. Load SafetyProfile (Redis cache HIT → JSON 역직렬화, MISS → buildSync)
            ProfileResult profileResult = safetyProfileBuilder.getWithCacheHit(sessionId.toString(), userId.toString());
            SafetyProfile profile = profileResult.profile();
            boolean safetyProfileCacheHit = profileResult.cacheHit();

            // 3. Safety checks (parallel in production; sequential with virtual threads)
            SecurityAssessment securityAssessment = securityRuleFilter.check(normalized);
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

            // 4. InputJudge (conditional)
            InputJudgeResult judgeResult = null;
            boolean inputJudgeCalled = false;
            if (inputJudge.shouldCallJudge(combined, profile)) {
                judgeResult = inputJudge.judge(normalized, combined, profile);
                inputJudgeCalled = true;
            }

            // 5. Working Memory (CBT counters) + Memory Context
            SessionDelta sessionDelta = workingMemory.getSessionDelta(sessionId);
            String cachedMemory = contextPreWarmer.getCachedContext(sessionId);
            boolean memoryCacheHit = cachedMemory != null;
            String memoryContext = memoryCacheHit
                    ? cachedMemory
                    : contextPreWarmer.buildContextSync(sessionId, userId, combined, profile);

            // 6. Policy decision (10-step)
            PolicyDecision decision = policyEngine.decide(combined, judgeResult, profile, sessionDelta);

            // 7. Execute based on decision
            String assistantContent;
            long llmTtftMs = 0;
            boolean crisisFlowTriggered = false;
            OutputPreFilterResult preFilterResult = OutputPreFilterResult.pass();
            OutputJudgeResult judgeActionResult = null;

            if (decision.action() == DecisionAction.SECURITY_REFUSAL) {
                assistantContent = securityRefusalTemplate.get();
                sendEvent(emitter, new SseEventDto.DeltaEvent(assistantContent, outboundMsgId));
                sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId, userSignal.emotionScore(), false, "security_refusal"));

            } else if (decision.action() == DecisionAction.CRISIS_FLOW) {
                crisisFlowTriggered = true;
                CrisisFlowService.CrisisHandleResult crisisResult =
                        crisisFlowService.handle(l1Result, userMessage, user, session, emitter, outboundMsgId,
                                userSignal.emotionScore());
                assistantContent = crisisResult.fixedResponse();

            } else if (decision.action() == DecisionAction.GENERATE) {
                // OutputGuard 실행 여부는 deliveryMode로 제어 (requireOutputGuard 필드는 감사 로그용)
                // GENERATE: build prompt with GenerationMode instruction
                String systemPrompt = promptBuilder.buildSystemPrompt(
                        decision.generationMode(), decision.interventionHints(), memoryContext);
                LlmRequest llmRequest = LlmRequest.of(LLM_MODEL, systemPrompt, userMessage);
                StringBuilder contentBuilder = new StringBuilder();

                DeliveryMode deliveryMode = decision.deliveryMode();

                boolean inputHadRiskSignal = combined.riskCandidate() || combined.emotionSpike();

                if (deliveryMode == DeliveryMode.BUFFER) {
                    // Buffer: complete first, then OutputGuard, then SSE
                    llmTtftMs = llmClient.stream(llmRequest, contentBuilder::append);
                    assistantContent = contentBuilder.toString();

                    preFilterResult = outputPreFilter.checkWithCrisisContext(assistantContent, inputHadRiskSignal);
                    if (!preFilterResult.passed()) {
                        judgeActionResult = outputJudge.judge(assistantContent, preFilterResult);
                        if (judgeActionResult != null) {
                            assistantContent = resolveOutputJudgeAction(
                                    judgeActionResult, assistantContent, l1Result, user, session, emitter, outboundMsgId,
                                    userSignal.emotionScore());
                            if (judgeActionResult.action() == OutputJudgeAction.CRISIS_FLOW) {
                                crisisFlowTriggered = true;
                            }
                        }
                    }
                    if (judgeActionResult == null || judgeActionResult.action() != OutputJudgeAction.CRISIS_FLOW) {
                        sendEvent(emitter, new SseEventDto.DeltaEvent(assistantContent, outboundMsgId));
                        sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId, userSignal.emotionScore(), false, "stop"));
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

                    llmTtftMs = llmClient.stream(llmRequest, chunk -> {
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
                    });

                    assistantContent = contentBuilder.toString();

                    CompletableFuture<OutputJudgeResult> judgeFuture = earlyJudgeFutureRef.get();
                    OutputPreFilterResult earlyFilter = earlyFilterRef.get();

                    if (earlyFilter != null) {
                        preFilterResult = earlyFilter;
                    }

                    if (judgeFuture == null) {
                        // No early stop — run post-stream pre-filter check
                        preFilterResult = outputPreFilter.checkWithCrisisContext(assistantContent, inputHadRiskSignal);
                        if (!preFilterResult.passed()) {
                            log.warn("OutputGuard post-stream: session={} reasons={}",
                                    sessionId, preFilterResult.failReasons());
                            final String fullContent = assistantContent;
                            final OutputPreFilterResult fullFilter = preFilterResult;
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
                        if (isCrisis) crisisFlowTriggered = true;

                        String replacedContent = switch (judgeActionResult.action()) {
                            case REWRITE -> judgeActionResult.rewrittenContent() != null
                                    ? judgeActionResult.rewrittenContent()
                                    : "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";
                            case REPLACE, CRISIS_FLOW -> "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";
                            case SEND -> null;
                        };

                        if (replacedContent != null) {
                            assistantContent = replacedContent;
                            sendEvent(emitter, new SseEventDto.DeltaReplaceEvent(assistantContent, outboundMsgId));
                            sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId,
                                    userSignal.emotionScore(), isCrisis, "replaced_by_guard"));
                        } else if (stopSendingDeltas.get()) {
                            // Stopped mid-stream but content is safe — restore via delta.replace
                            sendEvent(emitter, new SseEventDto.DeltaReplaceEvent(assistantContent, outboundMsgId));
                            sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId,
                                    userSignal.emotionScore(), false, "stop"));
                        } else {
                            sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId,
                                    userSignal.emotionScore(), false, "stop"));
                        }
                    } else {
                        sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId,
                                userSignal.emotionScore(), false, "stop"));
                    }

                } else {
                    // SPECULATIVE: stream immediately, no post-guard
                    llmTtftMs = llmClient.stream(llmRequest, chunk -> {
                        contentBuilder.append(chunk);
                        try {
                            sendEvent(emitter, new SseEventDto.DeltaEvent(chunk, outboundMsgId));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    assistantContent = contentBuilder.toString();
                    sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId,
                            userSignal.emotionScore(), false, "stop"));
                }
            } else {
                // FALLBACK 또는 미지원 action — 안전 응답 반환
                log.warn("Unhandled decision action: {} for session={}", decision.action(), sessionId);
                assistantContent = "지금 연결에 문제가 생겼어요. 잠시 후 다시 시도해주세요.";
                sendEvent(emitter, new SseEventDto.DeltaEvent(assistantContent, outboundMsgId));
                sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId, userSignal.emotionScore(), false, "error"));
            }

            // 8. Persist messages
            messagePersistenceService.saveConversation(sessionId, userId, userMessage, assistantContent, userSignal);

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
                    profile.source(), safetyProfileCacheHit, memoryCacheHit);

            emitter.complete();

        } catch (Exception e) {
            log.error("Conversation orchestration failed for session {}", sessionId, e);
            emitter.completeWithError(e);
        }
    }

    private String resolveOutputJudgeAction(
            OutputJudgeResult result,
            String originalContent,
            SafetyL1Result l1Result,
            User user,
            Session session,
            SseEmitter emitter,
            String outboundMsgId,
            Integer emotionScore) throws IOException {

        return switch (result.action()) {
            case SEND -> originalContent;
            case REWRITE -> result.rewrittenContent() != null ? result.rewrittenContent() : originalContent;
            case REPLACE -> "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";
            case CRISIS_FLOW -> {
                CrisisFlowService.CrisisHandleResult cr =
                        crisisFlowService.handle(l1Result, null, user, session, emitter, outboundMsgId, emotionScore);
                yield cr != null ? cr.fixedResponse() : "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";
            }
        };
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
