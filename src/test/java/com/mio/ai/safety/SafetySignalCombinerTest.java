package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.security.SecurityAssessment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafetySignalCombinerTest {

    private final SafetySignalCombiner combiner = new SafetySignalCombiner();

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
                ModerationResult.failOpen(),
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
                ModerationResult.failOpen(),
                null
        );

        assertThat(combined.requiresJudge()).isTrue();
    }

}
