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

    @Test
    @DisplayName("미래 부정 예측 표현은 fortune_telling biasType으로 저장한다")
    void future_negative_prediction_sets_fortune_telling() {
        UserMessageSignal signal = analyzer.analyze("안 좋게 흘러갈 것 같다는 생각이 자꾸 듭니다.");
        assertThat(signal.biasType()).isEqualTo("fortune_telling");
    }

    @Test
    @DisplayName("부정 필터링 표현은 mental_filter biasType으로 저장한다")
    void negative_filtering_sets_mental_filter() {
        UserMessageSignal signal1 = analyzer.analyze("전부 엉망인 것만 보여요. 좋은 건 하나도 없어요.");
        assertThat(signal1.biasType()).isEqualTo("mental_filter");

        UserMessageSignal signal2 = analyzer.analyze("제가 하는 일은 다 의미가 없는 것 같아요.");
        assertThat(signal2.biasType()).isEqualTo("mental_filter");
    }

    @Test
    @DisplayName("절망 텍스트는 MODERATE_DISTRESS 수준의 emotionScore를 반환한다")
    void hopelessness_text_returns_moderate_distress_score() {
        UserMessageSignal signal = analyzer.analyze("전부 엉망인 것만 보여요. 이 상태가 계속될까 봐 무서워요.");
        assertThat(signal.emotionScore()).isEqualTo(45);
    }
}
