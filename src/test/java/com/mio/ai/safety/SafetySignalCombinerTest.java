package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.moderation.ModerationStatus;
import com.mio.ai.security.SecurityAssessment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SafetySignalCombinerTest {

    private final SafetySignalCombiner combiner = new SafetySignalCombiner();

    @Test
    @DisplayName("맥락 마커로 강등된 위기 후보는 반드시 InputJudge를 거친다")
    void unverifiedHardCrisisRequiresJudge() {
        SafetyL1Result l1 = new SafetyL1Result(
                false,
                true,
                true,
                false,
                false,
                false,
                false,
                List.of("crisis_keyword:죽고싶다", "crisis_context_marker:third_person"),
                0.75
        );

        CombinedSignal combined = combiner.combine(
                SecurityAssessment.clean(),
                l1,
                ModerationResult.clear(),
                null
        );

        assertThat(combined.hardCrisis()).isFalse();
        assertThat(combined.hardCrisisUnverified()).isTrue();
        assertThat(combined.requiresJudge()).isTrue();
    }

    @Test
    @DisplayName("반복 부정 신호 단독도 InputJudge 발동 조건이다")
    void repetitiveNegativeRequiresJudge() {
        SafetyL1Result l1 = new SafetyL1Result(
                false,
                false,
                false,
                true,
                false,
                false,
                List.of("repetitive_negative"),
                0.0
        );

        CombinedSignal combined = combiner.combine(
                SecurityAssessment.clean(),
                l1,
                ModerationResult.clear(),
                null
        );

        assertThat(combined.requiresJudge()).isTrue();
    }

    @Test
    @DisplayName("감정 급락 신호 단독도 InputJudge 발동 조건이다")
    void emotionSpikeRequiresJudge() {
        SafetyL1Result l1 = new SafetyL1Result(
                false,
                false,
                true,
                false,
                false,
                false,
                List.of("emotion_spike"),
                0.0
        );

        CombinedSignal combined = combiner.combine(
                SecurityAssessment.clean(),
                l1,
                ModerationResult.clear(),
                null
        );

        assertThat(combined.requiresJudge()).isTrue();
    }

    @Test
    @DisplayName("의존 신호 단독도 InputJudge 발동 조건이다")
    void dependencyHintRequiresJudge() {
        SafetyL1Result l1 = new SafetyL1Result(
                false,
                false,
                false,
                false,
                true,
                false,
                List.of("dependency_phrase"),
                0.0
        );

        CombinedSignal combined = combiner.combine(
                SecurityAssessment.clean(),
                l1,
                ModerationResult.clear(),
                null
        );

        assertThat(combined.requiresJudge()).isTrue();
    }

    @Test
    @DisplayName("L0 판정을 못 받아온 결합 결과는 미해결 상태를 그대로 싣는다")
    void failOpenModerationIsCarriedAsUnresolved() {
        CombinedSignal combined = combiner.combine(
                SecurityAssessment.clean(),
                SafetyL1Result.clear(),
                ModerationResult.failOpen(),
                null
        );

        assertThat(combined.moderationStatus())
                .as("판정 부재가 여기서 지워지면 PolicyEngine 은 정상 판정과 구분할 수 없다")
                .isEqualTo(ModerationStatus.UNRESOLVED);
        assertThat(combined.l0Flagged()).isFalse();
    }

    @Test
    @DisplayName("판정 상태 없이 결합 결과를 만들 수 없다 — 기본값으로 축약하지 않는다")
    void rejectsMissingModerationStatusInsteadOfAssumingResolved() {
        assertThatNullPointerException().isThrownBy(() -> new CombinedSignal(
                com.mio.ai.security.SecurityLevel.CLEAN,
                com.mio.ai.security.AttackKind.NONE,
                false, false, false, false, false, false, false, false,
                SafetyL1Result.clear(), 0.0, false, null
        ));
    }

}
