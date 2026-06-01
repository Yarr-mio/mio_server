package com.mio.ai.prompt;

import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

    private static final String BASE_PROMPT =
            "당신은 Mio입니다. 따뜻하고 공감적인 AI 코칭 캐릭터로, " +
            "사용자의 감정을 진심으로 이해하고 지지합니다. " +
            "CBT(인지행동치료) 원칙에 기반해 사용자가 스스로 감정을 탐색할 수 있도록 돕습니다. " +
            "진단이나 처방을 내리지 않으며, 의존성을 강화하는 표현은 하지 않습니다. " +
            "응답은 2-4문장으로 간결하게 유지합니다.";

    private static final String SUPPORTIVE_INSTRUCTION =
            "\n\n[현재 세션 지시] 감정을 먼저 충분히 인정하고 공감하세요. " +
            "행동 제안이나 해결책은 최소화합니다. 사용자가 감정을 표현할 공간을 만들어주세요.";

    private static final String GUARDED_INSTRUCTION =
            "\n\n[현재 세션 지시] 분석적 발언을 삼가고 공감 위주로 응답하세요. " +
            "단정적 표현을 사용하지 마세요. 사용자의 말을 조심스럽게 반영하세요.";

    public String buildSystemPrompt(GenerationMode mode, InterventionHints hints) {
        String base = BASE_PROMPT + buildModeInstruction(mode);
        if (hints != null && !hints.suggestedCodes().isEmpty()) {
            base += buildHintsInstruction(hints);
        }
        return base;
    }

    private String buildModeInstruction(GenerationMode mode) {
        return switch (mode) {
            case SUPPORTIVE -> SUPPORTIVE_INSTRUCTION;
            case GUARDED -> GUARDED_INSTRUCTION;
            case NORMAL -> "";
            case CRISIS -> "";
        };
    }

    private String buildHintsInstruction(InterventionHints hints) {
        StringBuilder sb = new StringBuilder("\n\n[개입 힌트]");
        if (!hints.suggestedCodes().isEmpty()) {
            sb.append(" 권장 접근: ").append(String.join(", ", hints.suggestedCodes())).append(".");
        }
        if (!hints.avoidCodes().isEmpty()) {
            sb.append(" 피할 접근: ").append(String.join(", ", hints.avoidCodes())).append(".");
        }
        return sb.toString();
    }
}
