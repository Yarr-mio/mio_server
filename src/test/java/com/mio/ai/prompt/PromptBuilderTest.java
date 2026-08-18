package com.mio.ai.prompt;

import com.mio.ai.delivery.SafePrefixCatalog;
import com.mio.ai.plan.GenerationFreedom;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder();

    @Test
    @DisplayName("NORMAL 모드는 기본 프롬프트만 반환한다")
    void normal_mode_returns_base_prompt() {
        String prompt = builder.buildSystemPrompt(GenerationMode.NORMAL, InterventionHints.empty());
        assertThat(prompt).contains("미오");
        assertThat(prompt).doesNotContain("현재 세션 지시");
    }

    @Test
    @DisplayName("SUPPORTIVE 모드는 감정 인정 지시를 포함한다")
    void supportive_mode_includes_emotion_instruction() {
        String prompt = builder.buildSystemPrompt(GenerationMode.SUPPORTIVE, InterventionHints.empty());
        assertThat(prompt).contains("감정을 먼저 충분히 인정");
    }

    @Test
    @DisplayName("GUARDED 모드는 분석적 발언 삼가 지시를 포함한다")
    void guarded_mode_includes_guarded_instruction() {
        String prompt = builder.buildSystemPrompt(GenerationMode.GUARDED, InterventionHints.empty());
        assertThat(prompt).contains("분석적 발언을 삼가");
    }

    @Test
    @DisplayName("interventionHints가 있으면 프롬프트에 개입 힌트가 포함된다")
    void hints_included_when_present() {
        var hints = new InterventionHints(
                List.of("cognitive_restructuring"), List.of("avoidance"), null);
        String prompt = builder.buildSystemPrompt(GenerationMode.SUPPORTIVE, hints);
        assertThat(prompt).contains("cognitive_restructuring");
        assertThat(prompt).contains("avoidance");
    }

    @Test
    @DisplayName("빈 hints는 프롬프트에 개입 힌트 섹션을 추가하지 않는다")
    void empty_hints_no_hints_section() {
        String prompt = builder.buildSystemPrompt(GenerationMode.NORMAL, InterventionHints.empty());
        assertThat(prompt).doesNotContain("개입 힌트");
    }

    // ── 응답 계약의 프롬프트 주입 (이슈 #303 / #369, 로드맵 §5.7) ──────────────
    //
    // 계약을 검사만 하고 지시하지 않으면 위반이 정상 경로가 된다 — 모델은 제약을 모른 채
    // 쓰고 서버가 사후에 거른다. 그런데 #303 은 이 주입에 대한 테스트를 남기지 않았다.

    @Test
    @DisplayName("계약이 있는 계획은 질문 수·문장 수 상한을 프롬프트에 적는다")
    void contract_limits_are_written_into_the_prompt() {
        ResponsePlan plan = new ResponsePlan(ResponseAct.EMOTION_CHECK, GenerationFreedom.CONSTRAINED,
                1, 4, ResponsePlan.BASE_FORBIDDEN);

        String prompt = builder.buildSystemPrompt(GenerationMode.SUPPORTIVE, InterventionHints.empty(),
                null, "mio", null, plan);

        assertThat(prompt).contains("[응답 계약]");
        assertThat(prompt).contains("질문은 최대 1개");
        assertThat(prompt).contains("전체 4문장 이내");
    }

    @Test
    @DisplayName("금지 요소는 지시문으로도 전달된다")
    void forbidden_elements_become_instructions() {
        List<String> forbidden = new java.util.ArrayList<>(ResponsePlan.BASE_FORBIDDEN);
        forbidden.add("advice");
        forbidden.add("cbt_intervention");
        ResponsePlan plan = new ResponsePlan(ResponseAct.EMPATHIC_REFLECTION,
                GenerationFreedom.CONSTRAINED, 1, 3, forbidden);

        String prompt = builder.buildSystemPrompt(GenerationMode.GUARDED, InterventionHints.empty(),
                null, "mio", null, plan);

        assertThat(prompt).contains("조언·제안은 하지 마세요");
        assertThat(prompt).contains("다르게 해석해보자는 제안은 하지 마세요");
        assertThat(prompt).contains("진단·단정·결과 보장 표현을 쓰지 마세요");
    }

    @Test
    @DisplayName("응답 행위마다 다른 지시를 준다")
    void each_act_gets_its_own_instruction() {
        assertThat(promptFor(ResponseAct.EMPATHIC_REFLECTION)).contains("감정을 인정하고 반영");
        assertThat(promptFor(ResponseAct.EMOTION_CHECK)).contains("감정과 그 강도를 확인");
        assertThat(promptFor(ResponseAct.CLARIFY_CONTEXT)).contains("맥락을 확인");
    }

    @Test
    @DisplayName("UNPLANNED 턴에는 계약 지시를 넣지 않는다")
    void unplanned_turn_has_no_contract_instruction() {
        String prompt = builder.buildSystemPrompt(GenerationMode.NORMAL, InterventionHints.empty(),
                null, "mio", null, ResponsePlan.unplanned());

        // 계획하지 않은 턴에 상한을 적으면 계약이 없는데도 있는 것처럼 모델이 제약된다.
        assertThat(prompt).doesNotContain("[응답 계약]");
    }

    @Test
    @DisplayName("계획이 null 이어도 프롬프트 생성이 깨지지 않는다")
    void null_plan_is_tolerated() {
        String prompt = builder.buildSystemPrompt(GenerationMode.NORMAL, InterventionHints.empty(),
                null, "mio", null, null);

        assertThat(prompt).contains("미오");
        assertThat(prompt).doesNotContain("[응답 계약]");
    }

    @Test
    @DisplayName("서버가 첫 문장을 보낸 턴은 감정 인정을 반복하지 말라고 지시한다")
    void safe_prefix_turn_tells_the_model_not_to_repeat_the_acknowledgement() {
        ResponsePlan plan = new ResponsePlan(ResponseAct.EMOTION_CHECK,
                GenerationFreedom.CONSTRAINED, 1, 3, ResponsePlan.BASE_FORBIDDEN);

        String prompt = builder.buildSystemPrompt(GenerationMode.SUPPORTIVE,
                InterventionHints.empty(), null, "mio", null, plan, true);

        // 지시하지 않으면 사용자는 같은 인정을 두 번 읽는다 — 눈에 보이는 제품 퇴행이다.
        assertThat(prompt).contains("[이미 전달됨]");
        assertThat(prompt).contains("다시 쓰지 말고");
    }

    @Test
    @DisplayName("서버 문구 자체는 프롬프트에 넣지 않는다")
    void the_server_copy_itself_never_reaches_the_prompt() {
        ResponsePlan plan = new ResponsePlan(ResponseAct.EMOTION_CHECK,
                GenerationFreedom.CONSTRAINED, 1, 3, ResponsePlan.BASE_FORBIDDEN);

        String prompt = builder.buildSystemPrompt(GenerationMode.SUPPORTIVE,
                InterventionHints.empty(), null, "mio", null, plan, true);

        // 문구를 넣으면 모델이 그대로 따라 쓰는 것이 가장 흔한 반복 실패가 된다.
        new SafePrefixCatalog().reviewedCopy().values()
                .forEach(copy -> assertThat(prompt).doesNotContain(copy));
    }

    @Test
    @DisplayName("prefix 를 보내지 않은 턴에는 그 지시가 없다")
    void turns_without_a_prefix_do_not_get_the_instruction() {
        assertThat(promptFor(ResponseAct.EMOTION_CHECK)).doesNotContain("[이미 전달됨]");
    }

    private String promptFor(ResponseAct act) {
        ResponsePlan plan = new ResponsePlan(act, GenerationFreedom.CONSTRAINED,
                1, 4, ResponsePlan.BASE_FORBIDDEN);
        return builder.buildSystemPrompt(GenerationMode.SUPPORTIVE, InterventionHints.empty(),
                null, "mio", null, plan);
    }
}
