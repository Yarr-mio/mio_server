package com.mio.ai.qa.correction;

import com.mio.ai.policy.GenerationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[QA] 정정 측정 실행 설정 (환경 변수)")
class CorrectionRunConfigTest {

    private static CorrectionRunConfig parse(Map<String, String> env) {
        return CorrectionRunConfig.fromEnv(env::get);
    }

    @Test
    @DisplayName("세트 경로만 주면 나머지는 안전한 기본값이다 (요약 리포트, 반복 3, 두 모드, 두 팔)")
    void defaults() {
        CorrectionRunConfig config = parse(Map.of(CorrectionRunConfig.SET_PATH, "eval-private/x.json", CorrectionRunConfig.MAX_KRW, "1000"));

        assertThat(config.repeats()).isEqualTo(3);
        assertThat(config.modes()).containsExactly(GenerationMode.NORMAL, GenerationMode.SUPPORTIVE);
        assertThat(config.arms()).containsExactly(CorrectionPromptArm.BASELINE, CorrectionPromptArm.WITH_CORRECTION_BLOCK);
        assertThat(config.parallelism()).isEqualTo(4);
        assertThat(config.detail()).isEqualTo(CorrectionReport.Detail.SUMMARY);
        assertThat(config.archiveDir().toString().replace('\\', '/')).isEqualTo("eval-private/runs");
    }

    @Test
    @DisplayName("환경 변수로 반복·모드·팔·병렬도·리포트를 바꾼다 (대소문자·공백 허용)")
    void overrides() {
        CorrectionRunConfig config = parse(Map.of(
                CorrectionRunConfig.SET_PATH, "s.json",
                CorrectionRunConfig.REPEATS, " 5 ",
                CorrectionRunConfig.MODES, "normal",
                CorrectionRunConfig.ARMS, "baseline",
                CorrectionRunConfig.PARALLELISM, "2",
                CorrectionRunConfig.REPORT, "detail",
                CorrectionRunConfig.MAX_KRW, "20000",
                CorrectionRunConfig.KRW_PER_USD, "1300",
                CorrectionRunConfig.SAMPLE_SIZE, "20"));

        assertThat(config.repeats()).isEqualTo(5);
        assertThat(config.modes()).containsExactly(GenerationMode.NORMAL);
        assertThat(config.arms()).containsExactly(CorrectionPromptArm.BASELINE);
        assertThat(config.parallelism()).isEqualTo(2);
        assertThat(config.detail()).isEqualTo(CorrectionReport.Detail.DETAIL);
        assertThat(config.sampleSize()).isEqualTo(20);
        assertThat(config.budget().maxUsd()).isEqualByComparingTo("15.384615");
    }

    @Test
    @DisplayName("세트 경로가 없거나 값이 잘못되면 무엇이 문제인지 알려 주며 거부한다")
    void rejectsBadInput() {
        assertThatThrownBy(() -> parse(Map.of(CorrectionRunConfig.SET_PATH, "s")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MIO_EVAL_CORRECTION_MAX_KRW");
        assertThatThrownBy(() -> parse(Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MIO_EVAL_CORRECTION_SET_PATH");
        assertThatThrownBy(() -> parse(Map.of(CorrectionRunConfig.SET_PATH, "s", CorrectionRunConfig.MAX_KRW, "1000", CorrectionRunConfig.REPEATS, "0")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 이상");
        assertThatThrownBy(() -> parse(Map.of(CorrectionRunConfig.SET_PATH, "s", CorrectionRunConfig.MAX_KRW, "1000", CorrectionRunConfig.REPEATS, "many")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("정수");
        assertThatThrownBy(() -> parse(Map.of(CorrectionRunConfig.SET_PATH, "s", CorrectionRunConfig.MAX_KRW, "1000", CorrectionRunConfig.MODES, "LOUD")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOUD");
        assertThatThrownBy(() -> parse(Map.of(CorrectionRunConfig.SET_PATH, "s", CorrectionRunConfig.MAX_KRW, "1000", CorrectionRunConfig.ARMS, " , ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("비었다");
    }
}
