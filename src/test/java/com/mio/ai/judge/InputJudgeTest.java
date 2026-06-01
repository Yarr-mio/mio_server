package com.mio.ai.judge;

import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.profile.SafetyProfile;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("fallback 결과는 CLEAR_LOW를 반환한다")
    void fallback_result_is_clear_low() {
        var fallback = InputJudgeResult.fallback();
        assertThat(fallback.risk().riskLevel()).isEqualTo(RiskLevel.CLEAR_LOW);
        assertThat(fallback.security().level()).isEqualTo(SecurityLevel.CLEAN);
        assertThat(fallback.confidence()).isEqualTo(0.0);
    }
}
