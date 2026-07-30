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
    // JSON 판정 출력 상한. 예상 ~130 토큰이지만 여유를 크게 둔다 — JSON 은 잘리면 파싱이
    // 통째로 실패하고, 그러면 안전 판정이 사라진다 (fallback CLEAR_LOW + failed).
    private static final int JUDGE_MAX_COMPLETION_TOKENS = 500;

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
            LlmRequest request = LlmRequest.of(JUDGE_MODEL, SYSTEM_PROMPT, contextPrompt)
                    .withMaxCompletionTokens(JUDGE_MAX_COMPLETION_TOKENS);
            String responseJson = llmClient.completeJson(request);
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
        // security.level 이 없으면 "안전함"이 아니라 "판정하지 못함"이다. 이 판정은 이제
        // effectiveSecurity 결합에 실제로 쓰이므로(이슈 #262), CLEAN 으로 채우면 규칙이
        // 의심한 입력을 Judge 가 "깨끗하다"고 복구해버린다 — 없는 근거로 등급을 낮추는 셈이다.
        if (!secNode.hasNonNull("level")) {
            throw new IllegalStateException("InputJudge 응답에 security.level 이 없다");
        }
        SecurityLevel secLevel = parseSecurityLevel(secNode.path("level").asText());
        List<String> attackTypes = new ArrayList<>();
        secNode.path("attack_types").forEach(n -> attackTypes.add(n.asText()));
        // 가드 요구는 부재 시 켜는 쪽이 기본이다. 끄는 쪽을 기본으로 두면 필드 누락이
        // 곧 가드 해제가 된다.
        boolean requireOutputSecGuard = secNode.path("require_output_security_guard").asBoolean(true);
        SecurityVerdict security = new SecurityVerdict(secLevel, attackTypes, requireOutputSecGuard);

        JsonNode riskNode = root.path("risk");
        // risk_level 이 없으면 "위험 없음"이 아니라 "판정하지 못함"이다. 기본값 CLEAR_LOW 로 채우면
        // 잘린 응답이 그대로 저위험 판정이 되어, 강등된 위기를 해제하는 신호로 쓰인다
        // (PolicyEngine.crisisClearedByJudge). 판정 실패로 처리해 fail-closed 를 유지한다.
        if (!riskNode.hasNonNull("risk_level")) {
            throw new IllegalStateException("InputJudge 응답에 risk.risk_level 이 없다");
        }
        RiskLevel riskLevel = parseRiskLevel(riskNode.path("risk_level").asText());
        List<String> riskTypes = new ArrayList<>();
        riskNode.path("risk_types").forEach(n -> riskTypes.add(n.asText()));
        GenerationMode genMode = parseGenerationMode(riskNode.path("recommended_generation_mode").asText("NORMAL"));
        DeliveryMode delivery = parseDeliveryMode(riskNode.path("recommended_delivery").asText("SPECULATIVE"));
        boolean requireSafetyGuard = riskNode.path("require_output_safety_guard").asBoolean(true);
        RiskVerdict risk = new RiskVerdict(riskLevel, riskTypes, genMode, delivery, requireSafetyGuard);

        double confidence = root.path("confidence").asDouble(0.5);

        return new InputJudgeResult(security, risk, confidence);
    }

    /**
     * 알 수 없는 값을 CLEAN 으로 떨어뜨리지 않는다 ({@link #parseRiskLevel} 과 같은 이유).
     *
     * <p>이 판정은 {@code EffectiveSecurityResolver} 에서 규칙 판정과 결합된다. 스키마를 벗어난
     * 값이 CLEAN 이 되면 규칙이 SUSPICIOUS 로 본 입력을 "Judge 가 깨끗하다고 했다"며 되돌린다.
     * 판정 실패로 올려 규칙 판정이 유지되게 한다.
     */
    private SecurityLevel parseSecurityLevel(String value) {
        try {
            return SecurityLevel.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("InputJudge 가 알 수 없는 SecurityLevel 을 반환했다: " + value, e);
        }
    }

    /**
     * 알 수 없는 값을 CLEAR_LOW 로 떨어뜨리지 않는다. 그렇게 하면 모델이 스키마를 벗어난 값을
     * 반환했을 때 "판정 불가"가 "위험 없음"으로 둔갑해 위기 해제 신호가 된다.
     */
    private RiskLevel parseRiskLevel(String value) {
        try {
            return RiskLevel.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("InputJudge 가 알 수 없는 RiskLevel 을 반환했다: " + value, e);
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
