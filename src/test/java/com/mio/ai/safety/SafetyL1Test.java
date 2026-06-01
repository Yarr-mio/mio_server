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
    @DisplayName("직전 emotionScore 대비 30점 이상 하락하면 emotionSpike = true를 반환한다")
    void emotion_score_drop_triggers_emotion_spike() {
        var result = safetyL1.check(new SafetyL1Input(
                "방금 일이 생기고 나서 마음이 확 무너졌어요",
                List.of(new SafetyL1HistoryMessage("오늘은 괜찮았어요", 70, null)),
                ModerationResult.failOpen(),
                null,
                25,
                "catastrophizing"
        ));

        assertThat(result.emotionSpike()).isTrue();
        assertThat(result.hasAnySignal()).isTrue();
    }

    @Test
    @DisplayName("동일 biasType이 임계값만큼 반복되면 repetitiveNegative = true를 반환한다")
    void repeated_bias_type_triggers_repetitive_negative() {
        var result = safetyL1.check(new SafetyL1Input(
                "이번에도 또 안 됐어요",
                List.of(
                        new SafetyL1HistoryMessage("지난번에도 비슷했어요", 45, "overgeneralization"),
                        new SafetyL1HistoryMessage("저는 늘 이런 식이에요", 45, "overgeneralization")
                ),
                ModerationResult.failOpen(),
                null,
                45,
                "overgeneralization"
        ));

        assertThat(result.repetitiveNegative()).isTrue();
        assertThat(result.hasAnySignal()).isTrue();
    }

    @Test
    @DisplayName("catastrophizing biasType 단독 발화는 riskCandidate = true를 반환한다")
    void catastrophizing_bias_single_turn_triggers_risk_candidate() {
        var result = safetyL1.check(new SafetyL1Input(
                "이게 더 큰 문제로 번질까 봐 걱정돼요",
                List.of(),
                ModerationResult.failOpen(),
                null,
                45,
                "catastrophizing"
        ));
        assertThat(result.riskCandidate()).isTrue();
        assertThat(result.signals()).anyMatch(s -> s.startsWith("cognitive_distortion:catastrophizing"));
    }

    @Test
    @DisplayName("fortune_telling biasType 단독 발화는 riskCandidate = true를 반환한다")
    void fortune_telling_bias_single_turn_triggers_risk_candidate() {
        var result = safetyL1.check(new SafetyL1Input(
                "안 좋게 흘러갈 것 같다는 생각이 자꾸 들어요",
                List.of(),
                ModerationResult.failOpen(),
                null,
                45,
                "fortune_telling"
        ));
        assertThat(result.riskCandidate()).isTrue();
        assertThat(result.signals()).anyMatch(s -> s.startsWith("cognitive_distortion:fortune_telling"));
    }

    @Test
    @DisplayName("수동적 자살 사고(내가 없어도)는 riskCandidate = true를 반환한다")
    void passive_suicidal_ideation_triggers_risk_candidate() {
        var result = safetyL1.check(input("내가없어도다들괜찮지않을까하는생각이자꾸들어요"));
        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.riskCandidate()).isTrue();
        assertThat(result.signals()).anyMatch(s -> s.contains("risk_keyword"));
    }

    @Test
    @DisplayName("절망 패턴(hopelessness)은 riskCandidate = true를 반환한다")
    void hopelessness_pattern_triggers_risk_candidate() {
        var result1 = safetyL1.check(input("전부엉망인것만보여요이상태가계속될까봐무서워요"));
        assertThat(result1.riskCandidate()).isTrue();
        assertThat(result1.signals()).anyMatch(s -> s.startsWith("hopelessness"));

        var result2 = safetyL1.check(input("제가하는일은다의미가없는것같아요"));
        assertThat(result2.riskCandidate()).isTrue();
        assertThat(result2.signals()).anyMatch(s -> s.startsWith("hopelessness"));
    }

    @Test
    @DisplayName("hopelessness는 hardCrisis가 아닌 riskCandidate로 처리된다")
    void hopelessness_is_risk_not_crisis() {
        var result = safetyL1.check(input("아무것도의미없는것같고전부엉망인것만보여요"));
        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.riskCandidate()).isTrue();
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
