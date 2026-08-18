package com.mio.ai.prompt;

import com.mio.ai.plan.ResponseAct;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.character.domain.CharacterPersona;
import org.springframework.stereotype.Component;


@Component
public class PromptBuilder {


    private static final String SUPPORTIVE_INSTRUCTION =
            "\n\n[현재 세션 지시] 감정을 먼저 충분히 인정하고 공감하세요. " +
            "행동 제안이나 해결책은 최소화합니다. 사용자가 감정을 표현할 공간을 만들어주세요.";

    private static final String GUARDED_INSTRUCTION =
            "\n\n[현재 세션 지시] 분석적 발언을 삼가고 공감 위주로 응답하세요. " +
            "단정적 표현을 사용하지 마세요. 사용자의 말을 조심스럽게 반영하세요.";

    public String buildSystemPrompt(GenerationMode mode, InterventionHints hints) {
        return buildSystemPrompt(mode, hints, null, CharacterPersona.DEFAULT.characterId(), null);
    }

    public String buildSystemPrompt(GenerationMode mode, InterventionHints hints, String memoryContext) {
        return buildSystemPrompt(mode, hints, memoryContext, CharacterPersona.DEFAULT.characterId(), null);
    }

    public String buildSystemPrompt(GenerationMode mode, InterventionHints hints,
                                    String memoryContext, String characterId, String checkpointSummary) {
        return buildSystemPrompt(mode, hints, memoryContext, characterId, checkpointSummary, null);
    }

    /**
     * 응답 계약을 프롬프트 지시로 옮긴다 (이슈 #303).
     *
     * <p>계약을 검사만 하고 지시하지 않으면 위반이 정상 경로가 된다 — 모델은 제약을 모른 채
     * 쓰고, 서버는 그걸 사후에 거른다. 상한을 먼저 알려야 위반율이 실제로 떨어진다.
     */
    public String buildSystemPrompt(GenerationMode mode, InterventionHints hints,
                                    String memoryContext, String characterId, String checkpointSummary,
                                    ResponsePlan plan) {
        return buildSystemPrompt(mode, hints, memoryContext, characterId, checkpointSummary, plan, false);
    }

    /**
     * 서버가 첫 문장을 이미 보낸 턴을 프롬프트에 알린다 (P0-4, 로드맵 §5.6).
     *
     * <p>모델은 감정을 먼저 인정하도록 지시받는다. 서버가 그 문장을 이미 보냈는데 알리지
     * 않으면 사용자는 같은 인정을 두 번 읽는다 — 눈에 보이는 제품 퇴행이다.
     *
     * <p><b>문구 자체는 프롬프트에 넣지 않는다.</b> 넣으면 모델이 그대로 따라 쓰는 것이 가장
     * 흔한 실패가 된다. 필요한 정보는 "이미 전달됐다"는 사실뿐이고, 그것만 주면 반복이
     * 생길 수 있는 표면이 좁아진다. 전달 단계에서 잘라내는 방식은 택하지 않았다 — 모델 출력을
     * 서버가 편집하면 사용자가 읽은 텍스트와 저장·재생되는 텍스트가 갈라진다.
     */
    public String buildSystemPrompt(GenerationMode mode, InterventionHints hints,
                                    String memoryContext, String characterId, String checkpointSummary,
                                    ResponsePlan plan, boolean safePrefixDelivered) {
        String base = resolveBasePrompt(characterId) + buildModeInstruction(mode)
                + buildPlanInstruction(plan) + buildSafePrefixInstruction(plan, safePrefixDelivered);
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
        return CharacterPersona.findOrDefault(characterId).chatSystemPrompt();
    }

    private String buildModeInstruction(GenerationMode mode) {
        return switch (mode) {
            case SUPPORTIVE -> SUPPORTIVE_INSTRUCTION;
            case GUARDED -> GUARDED_INSTRUCTION;
            case NORMAL -> "";
            case CRISIS -> "";
        };
    }

    private String buildPlanInstruction(ResponsePlan plan) {
        if (plan == null || plan.responseAct() == ResponseAct.UNPLANNED) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n[응답 계약] ");
        sb.append(switch (plan.responseAct()) {
            case EMPATHIC_REFLECTION -> "감정을 인정하고 반영하는 응답만 하세요.";
            case EMOTION_CHECK -> "감정과 그 강도를 확인하는 응답을 하세요.";
            case CLARIFY_CONTEXT -> "무슨 일이 있었는지 맥락을 확인하는 응답을 하세요.";
            default -> "";
        });
        sb.append(" 질문은 최대 ").append(plan.maxQuestions()).append("개, ");
        sb.append("전체 ").append(plan.maxSentences()).append("문장 이내로 씁니다.");
        if (plan.forbiddenElements().contains("advice")) {
            sb.append(" 조언·제안은 하지 마세요.");
        }
        if (plan.forbiddenElements().contains("cbt_intervention")) {
            sb.append(" 생각의 근거를 따지거나 다르게 해석해보자는 제안은 하지 마세요.");
        }
        sb.append(" 진단·단정·결과 보장 표현을 쓰지 마세요.");
        return sb.toString();
    }

    private String buildSafePrefixInstruction(ResponsePlan plan, boolean safePrefixDelivered) {
        if (!safePrefixDelivered || plan == null || plan.responseAct() == ResponseAct.UNPLANNED) {
            return "";
        }
        return "\n\n[이미 전달됨] 감정을 인정하는 첫 문장은 서버가 이미 사용자에게 보냈습니다. "
                + "인사나 감정 인정을 다시 쓰지 말고, 곧바로 이번 턴의 응답 행위부터 시작하세요.";
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
