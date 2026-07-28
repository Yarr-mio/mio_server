package com.mio.ai.profile;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * Memory Layer의 durable artifacts를 얇은 구조화 JSON으로 사전 계산한 프로파일 (§17).
 * 원문 belief text 없음 — 구조화 필드만.
 *
 * @param degraded 근거 조회에 실패해 보수적 기본값으로 채운 프로파일인지 (이슈 #261).
 *                 {@code source} 는 "얼마나 개인화됐는가"를, 이 값은 "근거를 확인했는가"를 나타낸다.
 *                 둘은 독립이라 하나로 합치지 않는다.
 */
public record SafetyProfile(
        String userId,
        String source,                          // "default" | "personalized"
        Map<String, Double> dynamicThresholds,
        List<String> effectiveInterventions,
        List<String> ineffectiveInterventions,
        List<String> policyFlags,
        double riskPriorScore,
        int recentCrisisSeverityMax,
        List<String> commonDistortionCodes,

        // Phase 3-5 추가 필드 (§17.2)
        int activeNegativeBeliefCount,
        String copingStyle,                     // "avoidance" | "approach" | null
        List<String> dominantTriggerKinds,
        String sensitivityCap,                  // "normal" | "sensitive" | "restricted"
        boolean degraded
) {
    public static final String SOURCE_DEFAULT      = "default";
    public static final String SOURCE_PERSONALIZED = "personalized";

    /** degraded 도입 이전 시그니처 — 기존 호출부 호환용 (이슈 #261). */
    public SafetyProfile(
            String userId, String source,
            Map<String, Double> dynamicThresholds,
            List<String> effectiveInterventions,
            List<String> ineffectiveInterventions,
            List<String> policyFlags,
            double riskPriorScore,
            int recentCrisisSeverityMax,
            List<String> commonDistortionCodes,
            int activeNegativeBeliefCount,
            String copingStyle,
            List<String> dominantTriggerKinds,
            String sensitivityCap) {
        this(userId, source, dynamicThresholds,
                effectiveInterventions, ineffectiveInterventions, policyFlags,
                riskPriorScore, recentCrisisSeverityMax, commonDistortionCodes,
                activeNegativeBeliefCount, copingStyle, dominantTriggerKinds, sensitivityCap,
                false);
    }

    /** 기존 생성자와의 하위 호환 (13개 인자 → 기본값 채움) */
    public SafetyProfile(
            String userId, String source,
            Map<String, Double> dynamicThresholds,
            List<String> effectiveInterventions,
            List<String> ineffectiveInterventions,
            List<String> policyFlags,
            double riskPriorScore,
            int recentCrisisSeverityMax,
            List<String> commonDistortionCodes) {
        this(userId, source, dynamicThresholds,
                effectiveInterventions, ineffectiveInterventions, policyFlags,
                riskPriorScore, recentCrisisSeverityMax, commonDistortionCodes,
                0, null, List.of(), "sensitive", false);
    }

    // ── threshold accessors ──────────────────────────────────────

    public double emotionDropThreshold() {
        return dynamicThresholds.getOrDefault("emotion_drop_threshold", 30.0);
    }

    public int repetitiveNegativeCount() {
        return dynamicThresholds.getOrDefault("repetitive_negative_count", 3.0).intValue();
    }

    public int messageBurstCount() {
        return dynamicThresholds.getOrDefault("message_burst_count", 10.0).intValue();
    }

    public double burstWindowMinutes() {
        return dynamicThresholds.getOrDefault("burst_window_minutes", 5.0);
    }

    public boolean hasForceJudge() {
        return policyFlags != null && policyFlags.contains("force_judge");
    }

    /**
     * {@code isX()} 형태라 Jackson 이 게터로 인식해 {@code "personalized"} 필드를 JSON 에 함께
     * 써 넣는다. 그 필드는 레코드 컴포넌트가 아니라 역직렬화할 수 없으므로, 관대한 매퍼가
     * 무시해주지 않으면 캐시 로드가 통째로 실패한다. 파생값이니 직렬화에서 제외한다.
     */
    @JsonIgnore
    public boolean isPersonalized() {
        return SOURCE_PERSONALIZED.equals(source);
    }
}
