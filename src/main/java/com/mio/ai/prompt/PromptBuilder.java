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

    /**
     * 정정 대응 공통 규칙 (이슈 #551, #558). 사용자가 직전 AI 응답을 바로잡거나, 반박하거나,
     * 조언을 거절하거나, 같은 내용을 다시 설명하면 인정+재진술 두 문장으로만 응답하고 새
     * 조언·질문·격려로 넘어가지 않는다. 정정이 아닌 턴에는 모델이 스스로 판단해 적용하지 않는다.
     *
     * <p>측정 근거: {@code CorrectionPromptArm.CORRECTION_BLOCK}(v3 문구, 동일)로 튜닝용·평가용
     * 세트 양쪽에서 측정했다. 평가용 세트(60건, 블라인드, 2026-10-07)에서 정정 통과율이
     * 기준선 1.4%(NORMAL)·15.0%(SUPPORTIVE)에서 후보 82.9%·83.6%로 올랐고, 대조군(정정 아닌 턴)
     * 부작용은 1~2%p로 작았다. 남은 실패는 "조언 거절" 종류와 "완곡한 이견(weak)" 강도에
     * 몰려 있으나, 코드 강제(2단계) 없이 프롬프트만으로 반영하기로 결정했다(2026-10-07).
     *
     * <p>이 문구를 고치면 {@code CorrectionPromptArm} 의 {@code BASELINE} 팔(=이 클래스를 그대로
     * 씀)도 같이 바뀐다 — 그 팔이 이제 프로덕션 기준선이다. {@code WITH_CORRECTION_BLOCK} 팔은
     * 이 블록을 중복으로 한 번 더 붙이므로 더는 측정에 쓰지 않고 회귀 비교 이력으로만 남긴다.
     */
    private static final String CORRECTION_BLOCK = """
            [정정 대응]
            먼저 판단하세요. 사용자가 당신의 직전 말을 바로잡거나, 반박하거나, 제안을 거절하거나, 같은 내용을
            다시 설명하고 있나요?
            - 아니라면: 이 지시를 적용하지 말고 평소처럼 답하세요. 사용자가 바로잡지 않았는데 "제가 잘못
              짚었네요" 같은 사과나 인정을 하지 마세요.
            - 맞다면: 응답은 반드시 정확히 두 문장으로만 쓰세요. 세 번째 문장을 쓰고 싶은 충동이 들면 그게
              바로 금지된 덧붙이기이니, 쓰지 말고 두 번째 문장의 마침표에서 멈추세요.
              1. 첫 문장: 바로잡힌 내용을 짚어서 인정하세요 ("~가 아니었군요"). "그렇군요"만으로 끝내지
                 마세요.
              2. 둘째 문장: 사용자가 말한 내용을 당신의 말로 다시 정리하세요. 사용자가 말하지 않은 사정이나
                 원인을 덧붙이지 마세요.
              셋째 문장은 쓰지 마세요. 공감·위로("정말 힘드실 것 같아요", "고생 많으셨어요"), 의미 부여,
              조언이나 제안(일반 팁이든 상황에 맞춘 제안이든), 질문, "~하시면 좋겠어요"/"~않으셨으면
              해요"/"~도움이 될 수 있어요"/"~해 보세요"/"~중요해요" 같은 마무리는 전부 셋째 문장에
              해당합니다. 직전에 한 조언도 반복하지 마세요.

              나쁜 예(실제 위반 사례): "쉴 시간이 아예 없으신 거군요. 퇴근 후에도 계속 바쁘시니 정말
              힘드실 것 같아요." — 둘째 문장까지는 맞지만 셋째 문장에 공감을 덧붙여 규칙을 어겼습니다.
              좋은 예(같은 상황, 두 문장으로 끝남): "쉴 시간이 아예 없으신 거군요. 퇴근 후에도 쉴 틈이
              없으셨다는 거네요.\"""";

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
        base += buildHintsInstruction(hints, plan);
        if (checkpointSummary != null && !checkpointSummary.isBlank()) {
            base += "\n\n## 이전 대화 요약\n" + checkpointSummary;
        }
        if (memoryContext != null && !memoryContext.isBlank()) {
            base += "\n\n" + memoryContext;
        }
        return base + "\n\n" + CORRECTION_BLOCK;
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

    /**
     * 이슈 #545 STEP 4 — 힌트가 비어 있을 때(왜곡 2회 미만이거나 세션 상한 도달) 침묵하지
     * 않고 명시적으로 금지한다. 이전에는 힌트가 없으면 아무 지시도 안 나가서, 모델이 스스로
     * 소크라테스식 질문을 꺼내도 막을 방법이 없었다 — "4마디 4질문" 재현의 핵심 경로였다.
     *
     * <p>코드 리뷰 반영 — 힌트가 비어 있다는 사실만으로는 이 턴이 실제로 CBT 게이트가 닫힌
     * 턴인지 알 수 없다. 왜곡 이력이 전혀 없는 순수 잡담도 {@code generateHints()} 가 항상
     * 빈 힌트를 반환하므로, 이전에는 그런 턴에도 무조건 이 지시가 나갔다. {@code ResponsePlanner}
     * 의 CBT 게이트 판단이 정확히 그런 턴만 골라 계약을 {@code maxQuestions=0} 으로 강제하므로,
     * 그 계약이 실제로 걸린 턴({@code plan.maxQuestions() == 0})에만 지시를 낸다 — 계약 계층과
     * 프롬프트 계층의 적용 범위를 일치시킨다.
     */
    private String buildHintsInstruction(InterventionHints hints, ResponsePlan plan) {
        if (hints == null || hints.suggestedCodes().isEmpty()) {
            boolean cbtGateClosedThisTurn = plan != null && plan.maxQuestions() == 0;
            if (!cbtGateClosedThisTurn) {
                return "";
            }
            return "\n\n[CBT 질문 지시] 지금은 소크라테스식 질문을 하지 마세요. "
                    + "공감하고 경청하는 응답만 하세요.";
        }
        StringBuilder sb = new StringBuilder("\n\n[개입 힌트]");
        sb.append(" 권장 접근: ").append(String.join(", ", hints.suggestedCodes())).append(".");
        if (!hints.avoidCodes().isEmpty()) {
            sb.append(" 피할 접근: ").append(String.join(", ", hints.avoidCodes())).append(".");
        }
        return sb.toString();
    }
}
