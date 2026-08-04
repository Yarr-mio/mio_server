package com.mio.ai.safety;

import com.mio.ai.input.InputNormalizer;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.profile.SafetyProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyL1Test {

    private final SafetyL1 safetyL1 = new SafetyL1(new InputNormalizer());

    private SafetyL1Input input(String normalizedMessage) {
        return new SafetyL1Input(normalizedMessage, List.of(), ModerationResult.clear());
    }

    private SafetyL1Input inputWithProfile(String normalizedMessage, SafetyProfile profile) {
        return new SafetyL1Input(normalizedMessage, List.of(), ModerationResult.clear(), profile);
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

    @ParameterizedTest(name = "표기 우회는 즉시 위기 또는 검증 대기로 포착: {0}")
    @ValueSource(strings = {
            "ㅈㅜㄱ고싶다",
            "죽.고.싶.다",
            "ㅈㅏㅅㅏㄹ 생각중",
            "죽~~고~~싶~~다",
            "쥭고싶다",
            "ㅈㅏ해 하고싶어",
            "죽고시퍼",
            "kill myself 하고 싶어"
    })
    void crisisObfuscationIsCanonicalizedBeforeMatching(String message) {
        var result = safetyL1.check(input(message));

        assertThat(result.hardCrisis() || result.hardCrisisUnverified()).isTrue();
        assertThat(result.hasAnySignal()).isTrue();
    }

    @ParameterizedTest(name = "자모·Unicode 구분자 결합 우회도 안전 경로로 포착: {0}")
    @ValueSource(strings = {
            "ㅈ.ㅜ.ㄱ.고.싶.다",
            "ㅈ\u200Bㅜ\u200Bㄱ고싶다",
            "ㅈ\u2028ㅏ\u2028ㅅ\u2028ㅏ\u2028ㄹ 생각중",
            "죽\uFE0F고\uFE0F싶\uFE0F다",
            "죽\u200B고싶다",
            "죽\uFE0F고싶다",
            "죽\u2028고싶다",
            "죽.고싶다",
            "ㅈ.ㅜ.ㄱ.고싶다",
            "ᄌ\u200Bᅡ\u200Bᄉ\u200Bᅡ\u200Bᆯ",
            "ᄌ.ᅡ.ᄉ.ᅡ.ᆯ",
            "죽\u00A8고싶다",
            "죽\u00AF고싶다",
            "죽\u00B4고싶다"
    })
    void combinedCrisisObfuscationIsCanonicalized(String message) {
        var result = safetyL1.check(input(message));

        assertThat(result.hardCrisis() || result.hardCrisisUnverified()).isTrue();
    }

    @ParameterizedTest(name = "모든 HARD 위기어 계열의 가시 구분자 우회는 Judge 검증으로 포착: {0}")
    @ValueSource(strings = {
            "자.살 생각중",
            "자.해하고싶다",
            "목숨을.끊고싶다",
            "sui.cide",
            "kill.myself",
            "self.harm"
    })
    void visibleSeparatorObfuscationRequiresJudge(String message) {
        var result = safetyL1.check(input(message));

        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.hardCrisisUnverified()).isTrue();
        assertThat(result.riskCandidate()).isTrue();
        assertThat(result.signals()).anyMatch(s -> s.startsWith("crisis_obfuscated_keyword:"));
    }

    @ParameterizedTest(name = "문법적 구두점 경계는 위기 키워드로 합치지 않음: {0}")
    @ValueSource(strings = {
            "자, 살펴볼까요?",
            "자, 해볼까요?",
            "목숨을, 끊임없이 소중히 여기고 싶어요",
            "자, 살, 돈, 집"
    })
    void naturalPunctuationBoundaryDoesNotCreateHardCrisis(String message) {
        var result = safetyL1.check(input(message));

        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.hardCrisisUnverified()).isFalse();
        assertThat(result.riskCandidate()).isFalse();
    }

    @Test
    @DisplayName("위험 키워드는 riskCandidate = true를 반환한다")
    void risk_keyword_triggers_risk_candidate() {
        var result = safetyL1.check(input("사라지고싶다"));
        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.riskCandidate()).isTrue();
    }

    @ParameterizedTest(name = "계획·수단 표현은 즉시 확정하지 않고 검증 후보로 승격: {0}")
    @ValueSource(strings = {
            "약을 조금씩 모아뒀어요",
            "옥상에 몇 번 올라가봤어요",
            "유서를 써놨어요",
            "주변 정리를 다 끝냈어요",
            "다음 주 화요일로 날짜를 정했어요",
            "보험을 정리하고 왔어요"
    })
    void planAndMeansPhrasesBecomeReviewCandidates(String message) {
        var result = safetyL1.check(input(message));

        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.riskCandidate()).isTrue();
        assertThat(result.signals()).anyMatch(s -> s.startsWith("review_candidate:plan_means:"));
    }

    @ParameterizedTest(name = "간접 절망 표현은 검증 후보로 승격: {0}")
    @ValueSource(strings = {
            "이제 아무 기대도 안 해요",
            "매일이 그냥 견디는 일이에요",
            "나아질 거란 생각이 안 들어요",
            "아무한테도 짐이 되고 싶지 않아요",
            "내가 왜 여기 있는지 모르겠어요",
            "웃는 법을 잊어버린 것 같아요"
    })
    void indirectHopelessnessBecomesReviewCandidate(String message) {
        var result = safetyL1.check(input(message));

        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.riskCandidate()).isTrue();
        assertThat(result.signals()).anyMatch(s -> s.startsWith("review_candidate:hopelessness:"));
    }

    @ParameterizedTest(name = "수동적 자살 사고는 검증 후보로 승격: {0}")
    @ValueSource(strings = {
            "내일 아침에 안 깨어나도 상관없을 것 같아요",
            "사고라도 났으면 좋겠다는 생각을 해요",
            "굳이 살아야 할 이유를 모르겠어요",
            "잠들면 안 깨고 싶다는 생각을 자주 해요"
    })
    void passiveIdeationBecomesReviewCandidate(String message) {
        var result = safetyL1.check(input(message));

        assertThat(result.hardCrisis()).isFalse();
        assertThat(result.riskCandidate()).isTrue();
        assertThat(result.signals()).anyMatch(s -> s.startsWith("review_candidate:passive_ideation:"));
    }

    @ParameterizedTest(name = "일상 계획 문맥은 검증 후보가 되더라도 즉시 위기로 확정하지 않는다: {0}")
    @ValueSource(strings = {
            "여행 날짜를 정했어요",
            "이사 때문에 주변 정리를 다 끝냈어요",
            "보험 서류를 정리하고 왔어요"
    })
    void ambiguousPlanningContextNeverBecomesImmediateCrisis(String message) {
        assertThat(safetyL1.check(input(message)).hardCrisis()).isFalse();
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
                ModerationResult.clear(),
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
                ModerationResult.clear(),
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
                ModerationResult.clear(),
                null,
                45,
                "catastrophizing"
        ));
        assertThat(result.riskCandidate()).isTrue();
        assertThat(result.signals()).anyMatch(s -> s.startsWith("cognitive_distortion:catastrophizing"));
    }

    @Test
    @DisplayName("State 기준이 아닌 biasType 단독 발화는 riskCandidate로 처리하지 않는다")
    void non_state_bias_single_turn_does_not_trigger_risk_candidate() {
        var result = safetyL1.check(new SafetyL1Input(
                "안 좋게 흘러갈 것 같다는 생각이 자꾸 들어요",
                List.of(),
                ModerationResult.clear(),
                null,
                45,
                "fortune_telling"
        ));
        assertThat(result.riskCandidate()).isFalse();
        assertThat(result.signals()).noneMatch(s -> s.startsWith("cognitive_distortion:fortune_telling"));
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
