package com.mio.ai.qa.correction;

import java.util.List;
import java.util.Set;

/**
 * 정정 대응 시험 케이스 한 건 (이슈 #551).
 *
 * <p>정정은 "AI 가 이미 한 말"이 있어야 성립하므로 케이스는 멀티턴이다. 마지막 턴은 항상
 * 사용자 발화이고, 그 앞의 AI 발화는 <b>고정 대본</b>이다 — 모델이 실제로 생성하는 것은 마지막
 * 사용자 발화에 대한 응답 하나뿐이다.
 *
 * <p>기존 {@code CellRunner} 는 이전 턴에 사용자 발화만 싣고 AI 발화를 뺀다(약 458행).
 * 정정 케이스는 그 AI 발화가 핵심 입력이라 이 하네스가 따로 필요했다.
 *
 * @param type     정정 종류({@link #CORRECTION_TYPES}) 또는 정정이 아닌 대조군({@link #CONTROL_TYPE})
 * @param strength 정정 강도({@link #STRENGTHS}). 대조군은 비워 둔다
 * @param correction 이 마지막 발화가 정정인가 — 라벨. 대조군은 거짓
 * @param adviceRequested 사용자가 명시적으로 방법·조언을 요청했는가. 대조군에서만 참일 수 있다 —
 *                   정정 대응 지시가 정당한 조언까지 막는 부작용(채점 기준 B3)을 확인하는 라벨이다
 */
public record CorrectionEvalCase(String id, String type, String strength, boolean correction,
                                 boolean adviceRequested, List<Turn> turns) {

    /** 조언 요청 라벨이 없는 케이스. 기본값은 거짓이다. */
    public CorrectionEvalCase(String id, String type, String strength, boolean correction,
                              List<Turn> turns) {
        this(id, type, strength, correction, false, turns);
    }

    public static final Set<String> CORRECTION_TYPES = Set.of(
            "fact", "emotion", "advice_refusal", "soft_disagreement", "restatement");
    public static final String CONTROL_TYPE = "control";
    public static final Set<String> STRENGTHS = Set.of("strong", "medium", "weak");

    public record Turn(String role, String text) {
        public boolean isUser() {
            return "USER".equals(role);
        }

        public boolean isAssistant() {
            return "ASSISTANT".equals(role);
        }
    }

    public CorrectionEvalCase {
        require(id != null && !id.isBlank(), "?", "id 가 비었다");
        require(turns != null && !turns.isEmpty(), id, "turns 가 비었다");
        for (Turn turn : turns) {
            require(turn != null && (turn.isUser() || turn.isAssistant()), id,
                    "role 은 USER 또는 ASSISTANT 여야 한다");
            require(turn.text() != null && !turn.text().isBlank(), id, "turn text 가 비었다");
        }
        require(turns.get(turns.size() - 1).isUser(), id, "마지막 턴은 사용자 발화여야 한다");

        if (correction) {
            require(CORRECTION_TYPES.contains(type), id,
                    "정정 케이스의 type 은 %s 중 하나여야 한다: %s".formatted(CORRECTION_TYPES, type));
            require(STRENGTHS.contains(strength), id,
                    "정정 케이스의 strength 는 %s 중 하나여야 한다: %s".formatted(STRENGTHS, strength));
            require(turns.subList(0, turns.size() - 1).stream().anyMatch(Turn::isAssistant), id,
                    "정정 케이스에는 바로잡을 AI 직전 발화가 있어야 한다");
        } else {
            require(CONTROL_TYPE.equals(type), id,
                    "정정이 아닌 케이스의 type 은 %s 여야 한다: %s".formatted(CONTROL_TYPE, type));
        }
        require(!(correction && adviceRequested), id, "정정 케이스에는 adviceRequested 를 붙일 수 없다");
        turns = List.copyOf(turns);
    }

    public String userMessage() {
        return turns.get(turns.size() - 1).text();
    }

    public List<Turn> priorTurns() {
        return turns.subList(0, turns.size() - 1);
    }

    private static void require(boolean condition, String id, String message) {
        if (!condition) {
            throw new IllegalArgumentException("정정 시험 케이스 '%s': %s".formatted(id, message));
        }
    }
}
