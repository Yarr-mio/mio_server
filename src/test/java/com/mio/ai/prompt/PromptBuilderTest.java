package com.mio.ai.prompt;

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
}
