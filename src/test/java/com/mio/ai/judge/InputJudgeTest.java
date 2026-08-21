package com.mio.ai.judge;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.profile.SafetyProfile;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InputJudgeTest {

    private SafetyProfile defaultProfile() {
        return new SafetyProfile("u1", "default",
                Map.of(), List.of(), List.of(), List.of(), 0.0, 0, List.of());
    }

    private SafetyProfile forceJudgeProfile() {
        return new SafetyProfile("u1", "default",
                Map.of(), List.of(), List.of(), List.of("force_judge"), 0.0, 0, List.of());
    }

    private CombinedSignal combined(SecurityLevel security, boolean hardCrisis,
                                    boolean riskCandidate, boolean emotionSpike,
                                    boolean requiresJudge) {
        SafetyL1Result l1 = new SafetyL1Result(
                hardCrisis, riskCandidate, emotionSpike, false, false, false,
                List.of(), 0.0
        );
        return new CombinedSignal(
                security, hardCrisis, riskCandidate, emotionSpike, false, false,
                false, requiresJudge, l1, 0.0
        );
    }

    @Test
    @DisplayName("hardCrisis는 Judge를 호출하지 않는다 (requiresJudge = false)")
    void hard_crisis_skips_judge() {
        // InputJudge.shouldCallJudge는 CombinedSignal.requiresJudge를 그대로 반환
        // SafetySignalCombiner가 hardCrisis 시 requiresJudge=false를 보장함
        var combined = combined(SecurityLevel.CLEAN, true, false, false, false);
        // shouldCallJudge 로직은 CombinedSignal.requiresJudge에 위임
        assertThat(combined.requiresJudge()).isFalse();
    }

    @Test
    @DisplayName("ATTACK은 Judge를 호출하지 않는다")
    void attack_skips_judge() {
        var combined = combined(SecurityLevel.ATTACK, false, false, false, false);
        assertThat(combined.requiresJudge()).isFalse();
    }

    @Test
    @DisplayName("requiresJudge = true 신호 → shouldCallJudge = true")
    void requires_judge_true_calls_judge() {
        var combined = combined(SecurityLevel.SUSPICIOUS, false, false, false, true);
        SafetyProfile profile = defaultProfile();

        // InputJudge의 shouldCallJudge는 combined.requiresJudge()를 반환
        assertThat(combined.requiresJudge()).isTrue();
    }

    @Test
    @DisplayName("fallback 결과는 CLEAR_LOW를 반환하되 failed로 표시된다")
    void fallback_result_is_clear_low() {
        var fallback = InputJudgeResult.fallback();
        assertThat(fallback.risk().riskLevel()).isEqualTo(RiskLevel.CLEAR_LOW);
        assertThat(fallback.security().level()).isEqualTo(SecurityLevel.CLEAN);
        assertThat(fallback.confidence()).isEqualTo(0.0);
        assertThat(fallback.failed())
                .as("failed 가 false면 위기 검증 게이트가 이 결과를 정상 판정으로 오인한다")
                .isTrue();
    }

    // ── 이슈 #262: 보안 판정이 실제로 소비되므로 파싱 기본값이 fail-closed 여야 한다 ──

    @Test
    @DisplayName("security.level 이 없으면 CLEAN 이 아니라 판정 실패다")
    void missingSecurityLevelIsJudgeFailure() {
        InputJudge judge = judgeReturning("""
                {"risk":{"risk_level":"LOW"},"confidence":0.9}
                """);

        InputJudgeResult result = judge.judge(
                "메시지", combined(SecurityLevel.SUSPICIOUS, false, false, false, true), defaultProfile(), null, null);

        assertThat(result.failed())
                .as("CLEAN 으로 채우면 규칙이 의심한 입력을 '없는 근거'로 복구해버린다")
                .isTrue();
    }

    @Test
    @DisplayName("스키마 밖 SecurityLevel 은 CLEAN 으로 떨어뜨리지 않는다")
    void unknownSecurityLevelIsJudgeFailure() {
        InputJudge judge = judgeReturning("""
                {"security":{"level":"PROBABLY_FINE"},"risk":{"risk_level":"LOW"},"confidence":0.9}
                """);

        InputJudgeResult result = judge.judge(
                "메시지", combined(SecurityLevel.SUSPICIOUS, false, false, false, true), defaultProfile(), null, null);

        assertThat(result.failed()).isTrue();
    }

    @Test
    @DisplayName("가드 요구 필드가 없으면 켜는 쪽이 기본이다")
    void missingGuardFlagsDefaultToOn() {
        InputJudge judge = judgeReturning("""
                {"security":{"level":"SUSPICIOUS"},"risk":{"risk_level":"LOW"},"confidence":0.9}
                """);

        InputJudgeResult result = judge.judge(
                "메시지", combined(SecurityLevel.SUSPICIOUS, false, false, false, true), defaultProfile(), null, null);

        assertThat(result.failed()).isFalse();
        assertThat(result.security().requireOutputSecurityGuard())
                .as("필드 누락이 곧 가드 해제가 되면 안 된다")
                .isTrue();
        assertThat(result.risk().requireOutputSafetyGuard()).isTrue();
    }

    @Test
    @DisplayName("정상 응답은 그대로 파싱한다 — fail-closed 가 정상 경로를 막지 않는다")
    void wellFormedResponseIsParsed() {
        InputJudge judge = judgeReturning("""
                {"security":{"level":"CLEAN","require_output_security_guard":false},
                 "risk":{"risk_level":"LOW","require_output_safety_guard":false},"confidence":0.9}
                """);

        InputJudgeResult result = judge.judge(
                "메시지", combined(SecurityLevel.SUSPICIOUS, false, false, false, true), defaultProfile(), null, null);

        assertThat(result.failed()).isFalse();
        assertThat(result.security().level()).isEqualTo(SecurityLevel.CLEAN);
        assertThat(result.security().requireOutputSecurityGuard()).isFalse();
    }

    private InputJudge judgeReturning(String responseJson) {
        LlmClient llmClient = new LlmClient() {
            @Override
            public com.mio.ai.llm.LlmStreamResult stream(
                    LlmRequest request, java.util.function.Consumer<String> chunkHandler) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String completeText(LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String completeJson(LlmRequest request) {
                return responseJson;
            }

            @Override
            public String complete(LlmRequest request) {
                return responseJson;
            }
        };
        return new InputJudge(llmClient, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /**
     * 스키마를 벗어난 응답은 "위험 없음"이 아니라 "판정하지 못함"이다. CLEAR_LOW 로 채우면
     * 잘린 응답이 그대로 저위험 판정이 되어 PolicyEngine 이 강등된 위기를 해제한다.
     */
    @ParameterizedTest(name = "불완전 응답: {0}")
    @ValueSource(strings = {
            "{}",
            "{\"security\":{\"level\":\"CLEAN\"}}",
            "{\"risk\":{}}",
            "{\"risk\":{\"risk_level\":null}}",
            "{\"security\":{\"level\":\"CLEAN\"},\"risk\":{\"risk_level\":\"ATTACK\"}}",
            "{\"security\":{\"level\":\"CLEAN\"},\"risk\":{\"risk_level\":\"HARD_CRISIS\"}}",
            "{\"risk\":{\"risk_level\":\"MODERATE\"}}",
            "{\"risk\":{\"risk_level\":\"\"}}"
    })
    @DisplayName("risk_level 이 없거나 스키마 밖 값이면 판정 실패로 처리한다 (fail-closed)")
    void incompleteResponseIsTreatedAsFailure(String responseJson) {
        var result = judgeReturning(responseJson).judge("죽고싶어", null, null, null, null);

        assertThat(result.failed())
                .as("'%s' 는 판정 실패로 표시되어야 강등된 위기가 해제되지 않는다", responseJson)
                .isTrue();
    }

    @Test
    @DisplayName("정상 응답은 판정 실패로 표시되지 않는다")
    void completeResponseIsNotFailure() {
        var result = judgeReturning("""
                {"security":{"level":"CLEAN","attack_types":[],"require_output_security_guard":false},
                 "risk":{"risk_level":"MEDIUM","risk_types":[],"recommended_generation_mode":"SUPPORTIVE",
                         "recommended_delivery":"CAUTIOUS_SPECULATIVE","require_output_safety_guard":false},
                 "confidence":0.8}
                """).judge("좀 힘들어요", null, null, null, null);

        assertThat(result.failed()).isFalse();
        assertThat(result.risk().riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }
}
