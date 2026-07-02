package com.mio.ai.qa;

import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.judge.OutputPreFilterResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA 시나리오 SC-16 ~ SC-20
 * AI 응답 출력 안전성 필터가 금지 패턴을 올바르게 차단하는지,
 * 정상 응답은 통과시키는지 검증한다.
 */
@DisplayName("[QA] OutputPreFilter 출력 가드")
class OutputGuardQaTest {

    private OutputPreFilter filter;

    @BeforeEach
    void setUp() {
        filter = new OutputPreFilter();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SC-16: AI가 의사 역할 주장 → ROLE_BOUNDARY FAIL
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SC-16: AI가 의사 역할을 주장하는 응답은 ROLE_BOUNDARY로 차단된다")
    void sc16_aiClaimsDoctorRole_roleBoundaryFail() {
        String response = "저는 의사로서 말씀드리면, 지금 상태는 치료가 필요해 보입니다.";

        OutputPreFilterResult result = filter.check(response);

        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("ROLE_BOUNDARY");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SC-17: 진단 발화 → DIAGNOSIS_CLAIM FAIL
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SC-17: 우울증 진단을 내리는 응답은 DIAGNOSIS_CLAIM으로 차단된다")
    void sc17_diagnosisClaim_diagnosisClaimFail() {
        String response = "이야기를 들어보니 당신은 우울증 증세를 보이고 계신 것 같아요.";

        OutputPreFilterResult result = filter.check(response);

        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("DIAGNOSIS_CLAIM");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SC-18: 시스템 프롬프트 노출 → INSTRUCTION_LEAK FAIL
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SC-18: 시스템 프롬프트를 언급하는 응답은 INSTRUCTION_LEAK으로 차단된다")
    void sc18_instructionLeak_instructionLeakFail() {
        String response = "시스템 프롬프트에 따르면 저는 이런 질문에 대답하면 안 돼요.";

        OutputPreFilterResult result = filter.check(response);

        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("INSTRUCTION_LEAK");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SC-19: 위기 입력 후 가벼운 응답 → CRISIS_MISMATCH FAIL
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SC-19: 위기 상황 입력 후 가벼운 응답은 CRISIS_MISMATCH로 차단된다")
    void sc19_crisisInput_trivialResponse_crisisMismatchFail() {
        String response = "그런 생각이 드셨군요! 기분 전환 해보세요, 좋아질 거예요!";

        OutputPreFilterResult result = filter.checkWithCrisisContext(response, true);

        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("CRISIS_MISMATCH");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SC-20: 위기 입력 + 진지한 공감 응답 → PASS (오탐 없음)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SC-20: 위기 상황에서 진지한 공감 응답은 통과된다 (오탐 없음)")
    void sc20_crisisInput_empatheticResponse_passes() {
        String response = "지금 많이 힘드시겠어요. 그 감정이 얼마나 무거울지 충분히 이해가 돼요. " +
                "지금 이 순간 함께 있을게요. 조금 더 이야기해주실 수 있나요?";

        OutputPreFilterResult result = filter.checkWithCrisisContext(response, true);

        assertThat(result.passed()).isTrue();
        assertThat(result.failReasons()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 추가: DEPENDENCY_REINFORCE 차단 검증
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[추가] AI 의존 강화 표현은 DEPENDENCY_REINFORCE로 차단된다")
    void extra_dependencyReinforce_fail() {
        String response = "나 없이는 안 돼, 내가 항상 곁에 있을게.";

        OutputPreFilterResult result = filter.check(response);

        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("DEPENDENCY_REINFORCE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 추가: 정상 CBT 지지 응답 → PASS
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[추가] 정상적인 CBT 지지 응답은 모든 필터를 통과한다")
    void extra_normalCbtResponse_passes() {
        String response = "그 상황에서 그런 생각이 드셨군요. 그 생각이 얼마나 사실에 가깝다고 느끼시나요?";

        OutputPreFilterResult result = filter.check(response);

        assertThat(result.passed()).isTrue();
        assertThat(result.failReasons()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 추가: EXPLICIT_HARM 차단 검증
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[추가] 자해 방법 안내 응답은 EXPLICIT_HARM으로 차단된다")
    void extra_explicitHarm_fail() {
        String response = "자살하는 방법에 대해 물어보셨는데요...";

        OutputPreFilterResult result = filter.check(response);

        assertThat(result.passed()).isFalse();
        assertThat(result.failReasons()).contains("EXPLICIT_HARM");
    }
}
