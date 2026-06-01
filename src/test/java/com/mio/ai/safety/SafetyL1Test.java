package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.profile.SafetyProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyL1Test {

    private final SafetyL1 safetyL1 = new SafetyL1();

    private SafetyL1Input input(String normalizedMessage) {
        return new SafetyL1Input(normalizedMessage, List.of(), ModerationResult.failOpen());
    }

    private SafetyL1Input inputWithProfile(String normalizedMessage, SafetyProfile profile) {
        return new SafetyL1Input(normalizedMessage, List.of(), ModerationResult.failOpen(), profile);
    }

    @Test
    @DisplayName("일반 메시지는 모든 플래그가 false이다")
    void normal_message_all_flags_false() {
        var result = safetyL1.check(input("오늘날씨가좋네요"));
        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.riskCandidate()).isFalse();
        assertThat(result.hasAnySignal()).isFalse();
    }

    @Test
    @DisplayName("자살 키워드는 hardCrisis = true를 반환한다")
    void suicide_keyword_triggers_hard_crisis() {
        var result = safetyL1.check(input("죽고싶다"));
        assertThat(result.hardCrisis()).isTrue();
        assertThat(result.signals()).isNotEmpty();
    }

    @Test
    @DisplayName("자해 키워드는 hardCrisis = true를 반환한다")
    void self_harm_keyword_triggers_hard_crisis() {
        var result = safetyL1.check(input("자해하고싶다"));
        assertThat(result.hardCrisis()).isTrue();
    }

    @Test
    @DisplayName("위험 키워드는 riskCandidate = true를 반환한다")
    void risk_keyword_triggers_risk_candidate() {
        var result = safetyL1.check(input("사라지고싶다"));
        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.riskCandidate()).isTrue();
    }

    @Test
    @DisplayName("의존 표현은 dependencyHint = true를 반환한다")
    void dependency_phrase_triggers_dependency_hint() {
        var result = safetyL1.check(input("내얘기를들어주는건여기뿐인것같아요계속대답해주지않으면너무불안해요"));

        assertThat(result.dependencyHint()).isTrue();
        assertThat(result.hasAnySignal()).isTrue();
    }

    @Test
    @DisplayName("L0 self-harm flagged는 riskCandidate를 활성화한다")
    void l0_self_harm_activates_risk_candidate() {
        ModerationResult moderation = new ModerationResult(
                true,
                Map.of("self-harm", true),
                Map.of("self-harm", 0.8)
        );
        var inputObj = new SafetyL1Input("힘들어", List.of(), moderation);
        var result = safetyL1.check(inputObj);
        assertThat(result.moderationFlagged()).isTrue();
        assertThat(result.riskCandidate()).isTrue();
    }

    @Test
    @DisplayName("L0 장애(fail-open) 시 hardCrisis는 키워드로만 판단한다")
    void l0_fail_open_does_not_affect_hard_crisis_without_keyword() {
        var result = safetyL1.check(input("오늘너무힘들었어"));
        assertThat(result.hardCrisis()).isFalse();
    }

    @Test
    @DisplayName("combinedConfidence는 hardCrisis 시 0.9 이상이다")
    void combined_confidence_high_for_hard_crisis() {
        var result = safetyL1.check(input("자살"));
        assertThat(result.combinedConfidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    @DisplayName("SafetyProfile이 주입되면 동적 임계값을 사용한다 (null-safe)")
    void safety_profile_injection_does_not_break() {
        SafetyProfile profile = new SafetyProfile(
                "user-1", "default",
                Map.of("emotion_drop_threshold", 25.0, "repetitive_negative_count", 2.0),
                List.of(), List.of(), List.of(), 0.0, 0, List.of()
        );
        var result = safetyL1.check(inputWithProfile("죽고싶다", profile));
        assertThat(result.hardCrisis()).isTrue();
    }
}
