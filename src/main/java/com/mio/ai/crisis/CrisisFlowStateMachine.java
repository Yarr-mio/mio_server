package com.mio.ai.crisis;

import org.springframework.stereotype.Component;

/**
 * 자유 생성 없이 현재성·계획·수단·접근성·즉시 지원을 순서대로 확인하는 고정 상태기계.
 * 수단의 종류·위치·방법을 묻지 않으며 해석 불명확 시 즉시 고정 handoff로 종결한다.
 */
@Component
public class CrisisFlowStateMachine {

    private static final String RESOURCE_SUFFIX =
            " 지금 바로 연결이 필요하면 자살예방상담전화 109 또는 정신건강위기상담전화 1577-0199에 연락해주세요.";

    private static final String CURRENT_INTENT_RESPONSE =
            "지금 이 순간에도 스스로를 다치게 하거나 죽고 싶은 생각이 있나요? 예/아니오로만 답해주세요."
                    + RESOURCE_SUFFIX;
    private static final String PLAN_RESPONSE =
            "그 생각을 실제로 실행할 계획을 세워 두었나요? 계획의 내용은 말하지 말고 예/아니오로만 답해주세요."
                    + RESOURCE_SUFFIX;
    private static final String MEANS_RESPONSE =
            "그 계획에 쓰려는 물건이나 수단을 이미 정해 두었나요? 구체적인 내용은 말하지 말고 예/아니오로만 답해주세요."
                    + RESOURCE_SUFFIX;
    private static final String MEANS_ACCESS_RESPONSE =
            "그 수단에 지금 바로 접근할 수 있나요? 위치나 방법은 설명하지 말고 예/아니오로만 답해주세요."
                    + RESOURCE_SUFFIX;
    private static final String SUPPORT_RESPONSE =
            "지금 곁에 있거나 바로 연락할 수 있는 믿을 만한 사람이 있나요? 예/아니오로만 답해주세요."
                    + RESOURCE_SUFFIX;
    private static final String COMPLETED_RESPONSE =
            "지금 바로 그 사람에게 연락하고, 연결될 때까지 가능하면 혼자 있지 말아주세요. "
                    + "위험이 임박했다면 112나 119에 연락하고, 자살예방상담전화 109에도 바로 연락해주세요.";
    private static final String HANDOFF_RESPONSE =
            "답을 분명히 확인하지 못했어요. 지금은 일반 대화를 이어가지 않고 도움 연결을 우선할게요. "
                    + "자살예방상담전화 109 또는 정신건강위기상담전화 1577-0199에 바로 연락해주세요. "
                    + "위험이 임박했다면 112나 119에 연락해주세요.";

    public String initialResponse() {
        return CURRENT_INTENT_RESPONSE;
    }

    public String handoffResponse() {
        return HANDOFF_RESPONSE;
    }

    public CrisisFlowTransition next(CrisisFlowStage current, CrisisAnswer answer) {
        if (current == null || !current.isActive()) {
            throw new IllegalStateException("terminal or missing crisis stage cannot advance: " + current);
        }
        CrisisAnswer boundedAnswer = answer != null ? answer : CrisisAnswer.UNKNOWN;
        if (boundedAnswer == CrisisAnswer.UNKNOWN) {
            return handoff();
        }

        return switch (current) {
            case CURRENT_INTENT -> boundedAnswer == CrisisAnswer.YES
                    ? active(CrisisFlowStage.PLAN, PLAN_RESPONSE)
                    : active(CrisisFlowStage.IMMEDIATE_SUPPORT, SUPPORT_RESPONSE);
            case PLAN -> boundedAnswer == CrisisAnswer.YES
                    ? active(CrisisFlowStage.MEANS, MEANS_RESPONSE)
                    : active(CrisisFlowStage.IMMEDIATE_SUPPORT, SUPPORT_RESPONSE);
            case MEANS -> boundedAnswer == CrisisAnswer.YES
                    ? active(CrisisFlowStage.MEANS_ACCESS, MEANS_ACCESS_RESPONSE)
                    : active(CrisisFlowStage.IMMEDIATE_SUPPORT, SUPPORT_RESPONSE);
            case MEANS_ACCESS -> active(CrisisFlowStage.IMMEDIATE_SUPPORT, SUPPORT_RESPONSE);
            case IMMEDIATE_SUPPORT -> boundedAnswer == CrisisAnswer.YES
                    ? new CrisisFlowTransition(
                            CrisisFlowStage.COMPLETED, CrisisFlowStatus.COMPLETED, COMPLETED_RESPONSE)
                    : handoff();
            case COMPLETED, HANDOFF -> throw new IllegalStateException(
                    "terminal crisis stage cannot advance: " + current);
        };
    }

    private CrisisFlowTransition active(CrisisFlowStage stage, String response) {
        return new CrisisFlowTransition(stage, CrisisFlowStatus.ACTIVE, response);
    }

    private CrisisFlowTransition handoff() {
        return new CrisisFlowTransition(CrisisFlowStage.HANDOFF, CrisisFlowStatus.HANDOFF, HANDOFF_RESPONSE);
    }
}
