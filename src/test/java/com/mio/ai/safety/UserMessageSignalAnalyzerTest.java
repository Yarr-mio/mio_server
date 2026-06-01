package com.mio.ai.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMessageSignalAnalyzerTest {

    private final UserMessageSignalAnalyzer analyzer = new UserMessageSignalAnalyzer();

    @Test
    @DisplayName("강한 부정 정서는 낮은 emotionScore와 catastrophizing biasType으로 저장한다")
    void strong_distress_scores_low_and_sets_catastrophizing() {
        UserMessageSignal signal = analyzer.analyze("마음이 확 무너졌어요. 모든 게 끝장날 것 같아요.");

        assertThat(signal.emotionScore()).isEqualTo(25);
        assertThat(signal.biasType()).isEqualTo("catastrophizing");
    }

    @Test
    @DisplayName("반복 실패 표현은 overgeneralization biasType으로 저장한다")
    void repeated_failure_sets_overgeneralization() {
        UserMessageSignal signal = analyzer.analyze("이번에도 또 안 됐어요. 뭘 해도 결국 같은 결과가 나와요.");

        assertThat(signal.emotionScore()).isEqualTo(45);
        assertThat(signal.biasType()).isEqualTo("overgeneralization");
    }
}
