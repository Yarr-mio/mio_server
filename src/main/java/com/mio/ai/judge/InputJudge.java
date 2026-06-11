package com.mio.ai.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.profile.SafetyProfile;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.security.SecurityLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InputJudge {

    private static final String JUDGE_MODEL = "gpt-4o-mini";

    private static final String SYSTEM_PROMPT = """
            You are a safety classifier for a mental health coaching app.
            Analyze the user message and return a JSON with this exact schema:
            {
              "security": {
                "level": "CLEAN|SUSPICIOUS|ATTACK",
                "attack_types": [],
                "require_output_security_guard": false
              },
              "risk": {
                "risk_level": "CLEAR_LOW|LOW|MEDIUM|HIGH",
                "risk_types": [],
                "recommended_generation_mode": "NORMAL|SUPPORTIVE|GUARDED",
                "recommended_delivery": "SPECULATIVE|CAUTIOUS_SPECULATIVE|BUFFER",
                "require_output_safety_guard": false
              },
              "confidence": 0.0
            }

            risk_types values: casual_negative, ambiguous_distress, repetitive_negative, dependency_risk, crisis_possible, emotion_spike

            Risk level guidance:
            - HIGH: passive suicidal ideation (e.g. wondering if others would be fine without you, feeling existence is a burden), isolated with no one to talk to, recurring hopeless thoughts. Use GUARDED + BUFFER.
            - MEDIUM: clear emotional distress, dependency phrases, cognitive distortions without crisis signals. Use SUPPORTIVE + CAUTIOUS_SPECULATIVE.
            - LOW: mild negative mood, everyday worry or stress without clinical concern.
            - CLEAR_LOW: routine emotional sharing, no distress signals.

            Be conservative: when in doubt, prefer MEDIUM over LOW, HIGH over MEDIUM for crisis-adjacent signals.
            Respond ONLY with valid JSON.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public boolean shouldCallJudge(CombinedSignal combined, SafetyProfile profile) {
        return combined.requiresJudge();
    }

    public InputJudgeResult judge(String message, CombinedSignal combined, SafetyProfile profile) {
        try {
            String contextPrompt = buildContextPrompt(profile, message);
            LlmRequest request = LlmRequest.of(JUDGE_MODEL, SYSTEM_PROMPT, contextPrompt);
            String responseJson = llmClient.complete(request);
            return parseJudgeResult(responseJson);
        } catch (Exception e) {
            log.warn("InputJudge failed, using fallback CLEAR_LOW: {}", e.getMessage());
            return InputJudgeResult.fallback();
        }
    }

    private String buildContextPrompt(SafetyProfile profile, String message) {
        StringBuilder sb = new StringBuilder();
        if (profile != null && !profile.commonDistortionCodes().isEmpty()) {
            sb.append("[User Risk Context]\n");
            sb.append("- common_distortion_codes: ").append(profile.commonDistortionCodes()).append("\n");
            sb.append("- recent_crisis_severity_max: ").append(profile.recentCrisisSeverityMax()).append("\n");
            sb.append("\n");
        }
        sb.append("[Current Message]\n").append(message);
        return sb.toString();
    }

    private InputJudgeResult parseJudgeResult(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        JsonNode secNode = root.path("security");
        SecurityLevel secLevel = parseSecurityLevel(
                secNode.hasNonNull("level") ? secNode.path("level").asText() : "CLEAN");
        List<String> attackTypes = new ArrayList<>();
        secNode.path("attack_types").forEach(n -> attackTypes.add(n.asText()));
        boolean requireOutputSecGuard = secNode.path("require_output_security_guard").asBoolean(false);
        SecurityVerdict security = new SecurityVerdict(secLevel, attackTypes, requireOutputSecGuard);

        JsonNode riskNode = root.path("risk");
        RiskLevel riskLevel = parseRiskLevel(
                riskNode.hasNonNull("risk_level") ? riskNode.path("risk_level").asText() : "CLEAR_LOW");
        List<String> riskTypes = new ArrayList<>();
        riskNode.path("risk_types").forEach(n -> riskTypes.add(n.asText()));
        GenerationMode genMode = parseGenerationMode(riskNode.path("recommended_generation_mode").asText("NORMAL"));
        DeliveryMode delivery = parseDeliveryMode(riskNode.path("recommended_delivery").asText("SPECULATIVE"));
        boolean requireSafetyGuard = riskNode.path("require_output_safety_guard").asBoolean(false);
        RiskVerdict risk = new RiskVerdict(riskLevel, riskTypes, genMode, delivery, requireSafetyGuard);

        double confidence = root.path("confidence").asDouble(0.5);

        return new InputJudgeResult(security, risk, confidence);
    }

    private SecurityLevel parseSecurityLevel(String value) {
        try {
            return SecurityLevel.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown SecurityLevel from LLM: {}, defaulting to CLEAN", value);
            return SecurityLevel.CLEAN;
        }
    }

    private RiskLevel parseRiskLevel(String value) {
        try {
            return RiskLevel.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown RiskLevel from LLM: {}, defaulting to CLEAR_LOW", value);
            return RiskLevel.CLEAR_LOW;
        }
    }

    private GenerationMode parseGenerationMode(String value) {
        try {
            return GenerationMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GenerationMode.NORMAL;
        }
    }

    private DeliveryMode parseDeliveryMode(String value) {
        try {
            return DeliveryMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DeliveryMode.SPECULATIVE;
        }
    }
}
