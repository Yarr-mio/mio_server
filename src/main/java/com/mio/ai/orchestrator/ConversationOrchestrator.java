package com.mio.ai.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisFlowService;
import com.mio.ai.input.InputNormalizer;
import com.mio.ai.input.SecurityRuleFilter;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.moderation.OpenAiModerationClient;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.policy.PolicyEngine;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1;
import com.mio.ai.safety.SafetyL1Input;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.safety.SafetySignalCombiner;
import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.SecurityRefusalTemplate;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Message;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationOrchestrator {

    private static final String LLM_MODEL = "gpt-4o";

    private static final String SYSTEM_PROMPT =
            "당신은 Mio입니다. 따뜻하고 공감적인 AI 코칭 캐릭터로, " +
            "사용자의 감정을 진심으로 이해하고 지지합니다. " +
            "CBT(인지행동치료) 원칙에 기반해 사용자가 스스로 감정을 탐색할 수 있도록 돕습니다. " +
            "진단이나 처방을 내리지 않으며, 의존성을 강화하는 표현은 하지 않습니다. " +
            "응답은 2-4문장으로 간결하게 유지합니다.";

    private final InputNormalizer inputNormalizer;
    private final SecurityRuleFilter securityRuleFilter;
    private final OpenAiModerationClient moderationClient;
    private final SafetyL1 safetyL1;
    private final SafetySignalCombiner signalCombiner;
    private final PolicyEngine policyEngine;
    private final LlmClient llmClient;
    private final CrisisFlowService crisisFlowService;
    private final SecurityRefusalTemplate securityRefusalTemplate;
    private final AiDecisionLogger decisionLogger;
    private final SessionMessagePersistenceService messagePersistenceService;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

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

            // 2. Safety checks (parallel in production; sequential acceptable here with virtual threads)
            SecurityAssessment securityAssessment = securityRuleFilter.check(normalized);
            ModerationResult moderation = moderationClient.moderate(normalized);
            SafetyL1Result l1Result = safetyL1.check(
                    new SafetyL1Input(normalized, List.of(), moderation));
            CombinedSignal combined = signalCombiner.combine(securityAssessment, l1Result, moderation);

            // 3. Policy decision
            PolicyDecision decision = policyEngine.decide(combined);

            // 4. Execute based on decision
            String assistantContent;
            long llmTtftMs = 0;
            boolean crisisFlowTriggered = false;

            if (decision.action() == DecisionAction.SECURITY_REFUSAL) {
                assistantContent = securityRefusalTemplate.get();
                sendEvent(emitter, new SseEventDto.DeltaEvent(assistantContent, outboundMsgId));
                sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId, null, false, "stop"));

            } else if (decision.action() == DecisionAction.CRISIS_FLOW) {
                crisisFlowTriggered = true;
                CrisisFlowService.CrisisHandleResult crisisResult =
                        crisisFlowService.handle(l1Result, userMessage, user, session, emitter, outboundMsgId);
                assistantContent = crisisResult.fixedResponse();

            } else {
                // GENERATE: stream from LLM
                LlmRequest llmRequest = LlmRequest.of(LLM_MODEL, SYSTEM_PROMPT, userMessage);
                StringBuilder contentBuilder = new StringBuilder();

                llmTtftMs = llmClient.stream(llmRequest, chunk -> {
                    contentBuilder.append(chunk);
                    try {
                        sendEvent(emitter, new SseEventDto.DeltaEvent(chunk, outboundMsgId));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                assistantContent = contentBuilder.toString();
                sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId, null, false, "stop"));
            }

            // 5. Persist messages
            messagePersistenceService.saveConversation(sessionId, userId, userMessage, assistantContent);

            // 6. Log decision asynchronously
            long totalMs = System.currentTimeMillis() - startMs;
            decisionLogger.log(userId, sessionId, decision, moderation, l1Result,
                    securityAssessment, totalMs, llmTtftMs, crisisFlowTriggered);

            emitter.complete();

        } catch (Exception e) {
            log.error("Conversation orchestration failed for session {}", sessionId, e);
            emitter.completeWithError(e);
        }
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
