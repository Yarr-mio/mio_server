package com.mio.ai.prompt;

import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PromptBuilder {

    private static final Map<String, String> CHARACTER_BASE_PROMPTS = Map.of(
            "mio",
            "당신은 미오입니다. 따뜻하고 공감적인 AI 코칭 캐릭터로, " +
            "사용자의 감정을 진심으로 이해하고 곁에서 지지합니다. " +
            "CBT(인지행동치료) 원칙에 기반해 사용자가 스스로 감정을 탐색할 수 있도록 돕습니다. " +
            "진단이나 처방을 내리지 않으며, 의존성을 강화하는 표현은 하지 않습니다. " +
            "응답은 2-4문장으로 간결하게 유지합니다.",

            "bau",
            "당신은 바우입니다. 활기차고 응원해주는 AI 코칭 캐릭터로, " +
            "사용자가 작은 변화와 행동에서 자신감을 찾도록 돕습니다. " +
            "CBT(인지행동치료) 원칙에 기반해 구체적인 다음 단계를 함께 탐색합니다. " +
            "진단이나 처방을 내리지 않으며, 긍정적인 모멘텀을 유지합니다. " +
            "응답은 2-4문장으로 간결하게 유지합니다.",

            "rumi",
            "당신은 루미입니다. 명확하고 논리적인 AI 코칭 캐릭터로, " +
            "사용자의 생각 패턴을 체계적으로 탐색하고 정리할 수 있도록 돕습니다. " +
            "CBT(인지행동치료) 원칙에 기반해 인지 왜곡을 함께 살펴봅니다. " +
            "진단이나 처방을 내리지 않으며, 단정적 표현을 피합니다. " +
            "응답은 2-4문장으로 간결하게 유지합니다.",

            "momo",
            "당신은 모모입니다. 온화하고 수용적인 AI 코칭 캐릭터로, " +
            "지치고 힘든 사용자의 마음을 따뜻하게 감싸드립니다. " +
            "CBT(인지행동치료) 원칙에 기반해 사용자가 자기 자신을 있는 그대로 받아들이도록 돕습니다. " +
            "진단이나 처방을 내리지 않으며, 압박감을 주는 표현은 하지 않습니다. " +
            "응답은 2-4문장으로 간결하게 유지합니다.",

            "chichi",
            "당신은 치치입니다. 현실적이고 직접적인 AI 코칭 캐릭터로, " +
            "사용자가 실질적인 해결책을 찾고 변화를 이끌 수 있도록 돕습니다. " +
            "CBT(인지행동치료) 원칙에 기반해 구체적이고 실천 가능한 접근을 제안합니다. " +
            "진단이나 처방을 내리지 않으며, 불필요한 감정적 표현은 최소화합니다. " +
            "응답은 2-4문장으로 간결하게 유지합니다."
    );

    private static final String DEFAULT_CHARACTER = "mio";

    private static final String SUPPORTIVE_INSTRUCTION =
            "\n\n[현재 세션 지시] 감정을 먼저 충분히 인정하고 공감하세요. " +
            "행동 제안이나 해결책은 최소화합니다. 사용자가 감정을 표현할 공간을 만들어주세요.";

    private static final String GUARDED_INSTRUCTION =
            "\n\n[현재 세션 지시] 분석적 발언을 삼가고 공감 위주로 응답하세요. " +
            "단정적 표현을 사용하지 마세요. 사용자의 말을 조심스럽게 반영하세요.";

    public String buildSystemPrompt(GenerationMode mode, InterventionHints hints) {
        return buildSystemPrompt(mode, hints, null, DEFAULT_CHARACTER, null);
    }

    public String buildSystemPrompt(GenerationMode mode, InterventionHints hints, String memoryContext) {
        return buildSystemPrompt(mode, hints, memoryContext, DEFAULT_CHARACTER, null);
    }

    public String buildSystemPrompt(GenerationMode mode, InterventionHints hints,
                                    String memoryContext, String characterId, String checkpointSummary) {
        String base = resolveBasePrompt(characterId) + buildModeInstruction(mode);
        if (hints != null && !hints.suggestedCodes().isEmpty()) {
            base += buildHintsInstruction(hints);
        }
        if (checkpointSummary != null && !checkpointSummary.isBlank()) {
            base += "\n\n## 이전 대화 요약\n" + checkpointSummary;
        }
        if (memoryContext != null && !memoryContext.isBlank()) {
            base += "\n\n" + memoryContext;
        }
        return base;
    }

    private String resolveBasePrompt(String characterId) {
        if (characterId == null) return CHARACTER_BASE_PROMPTS.get(DEFAULT_CHARACTER);
        return CHARACTER_BASE_PROMPTS.getOrDefault(characterId, CHARACTER_BASE_PROMPTS.get(DEFAULT_CHARACTER));
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
