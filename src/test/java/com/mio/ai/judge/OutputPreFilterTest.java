package com.mio.ai.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputPreFilterTest {

    private final OutputPreFilter filter = new OutputPreFilter();

    @Test
    @DisplayName("정상 응답은 PASS를 반환한다")
    void normal_response_passes() {
        var result = filter.check("지금 많이 힘드시겠어요. 함께 이야기해봐요.");
        assertThat(result.passed()).isTrue();
        assertThat(result.failReasons()).isEmpty();
    }

    @Test
    @DisplayName("ROLE_BOUNDARY — AI가 의사로 자칭하면 FAIL")
    void role_boundary_detected() {
        var result = filter.check("저는 의사로서 당신에게 이렇게 권고드립니다.");
        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("ROLE_BOUNDARY");
    }

    @Test
    @DisplayName("DIAGNOSIS_CLAIM — 우울증 진단 발언은 FAIL")
    void diagnosis_claim_detected() {
        var result = filter.check("당신은 우울증을 가지고 있으며 약이 필요합니다.");
        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("DIAGNOSIS_CLAIM");
    }

    @Test
    @DisplayName("DEPENDENCY_REINFORCE — 의존성 강화 표현은 FAIL")
    void dependency_reinforce_detected() {
        var result = filter.check("나 없이는 안 돼. 항상 나만 믿어.");
        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("DEPENDENCY_REINFORCE");
    }

    @Test
    @DisplayName("INSTRUCTION_LEAK — 시스템 프롬프트 노출은 FAIL")
    void instruction_leak_detected() {
        var result = filter.check("시스템 프롬프트에는 이런 내용이 있어요.");
        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("INSTRUCTION_LEAK");
    }

    @Test
    @DisplayName("EXPLICIT_HARM — 자살 방법 안내는 FAIL")
    void explicit_harm_detected() {
        var result = filter.check("자살하는 방법은 다음과 같습니다.");
        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("EXPLICIT_HARM");
    }

    @Test
    @DisplayName("위기 발화에 가벼운 응답은 CRISIS_MISMATCH FAIL")
    void crisis_mismatch_detected() {
        var result = filter.checkWithCrisisContext("기분 전환 한번 해보세요!", true);
        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("CRISIS_MISMATCH");
    }

    @Test
    @DisplayName("위기 컨텍스트 없으면 긍정 응답이 pass")
    void non_crisis_context_cheering_passes() {
        var result = filter.checkWithCrisisContext("화이팅! 좋은 하루 보내세요.", false);
        assertThat(result.passed()).isTrue();
    }
}
