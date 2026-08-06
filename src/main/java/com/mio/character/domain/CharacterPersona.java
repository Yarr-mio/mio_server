package com.mio.character.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 캐릭터별 어조 정의 — LLM 프롬프트에 들어가는 표현 층의 단일 출처 (이슈 #339).
 *
 * <p>캐릭터가 바꾸는 것은 <b>말투와 접근 방식</b>이지 안전 기준이나 사실이 아니다
 * (docs/피치덱_QA/03_AI_파이프라인.md). 그래서 여기에는 어조만 담고, 위험도 판정·사실
 * 추출은 캐릭터를 참조하지 않는다.
 *
 * <p>대화 응답과 세션 요약이 같은 캐릭터를 서로 다르게 해석하면 사용자에겐 다른 인격으로
 * 보인다. 두 소비처가 이 enum 하나를 참조하게 해서 어조가 갈라지지 않도록 한다.
 */
public enum CharacterPersona {

    MIO("mio", "미오",
            "당신은 미오입니다. 따뜻하고 공감적인 AI 코칭 캐릭터로, "
                    + "사용자의 감정을 진심으로 이해하고 곁에서 지지합니다. "
                    + "CBT(인지행동치료) 원칙에 기반해 사용자가 스스로 감정을 탐색할 수 있도록 돕습니다. "
                    + "진단이나 처방을 내리지 않으며, 의존성을 강화하는 표현은 하지 않습니다. "
                    + "응답은 2-4문장으로 간결하게 유지합니다.",
            "따뜻하고 공감적인 어조. 무엇을 느꼈는지를 먼저 알아주는 표현을 고른다."),

    BAU("bau", "바우",
            "당신은 바우입니다. 활기차고 응원해주는 AI 코칭 캐릭터로, "
                    + "사용자가 작은 변화와 행동에서 자신감을 찾도록 돕습니다. "
                    + "CBT(인지행동치료) 원칙에 기반해 구체적인 다음 단계를 함께 탐색합니다. "
                    + "진단이나 처방을 내리지 않으며, 긍정적인 모멘텀을 유지합니다. "
                    + "응답은 2-4문장으로 간결하게 유지합니다.",
            "활기차고 응원하는 어조. 시도한 것과 움직인 부분에 초점을 둔다."),

    RUMI("rumi", "루미",
            "당신은 루미입니다. 명확하고 논리적인 AI 코칭 캐릭터로, "
                    + "사용자의 생각 패턴을 체계적으로 탐색하고 정리할 수 있도록 돕습니다. "
                    + "CBT(인지행동치료) 원칙에 기반해 인지 왜곡을 함께 살펴봅니다. "
                    + "진단이나 처방을 내리지 않으며, 단정적 표현을 피합니다. "
                    + "응답은 2-4문장으로 간결하게 유지합니다.",
            "명확하고 차분한 어조. 생각이 어떻게 이어졌는지를 정리해 보여준다."),

    MOMO("momo", "모모",
            "당신은 모모입니다. 온화하고 수용적인 AI 코칭 캐릭터로, "
                    + "지치고 힘든 사용자의 마음을 따뜻하게 감싸드립니다. "
                    + "CBT(인지행동치료) 원칙에 기반해 사용자가 자기 자신을 있는 그대로 받아들이도록 돕습니다. "
                    + "진단이나 처방을 내리지 않으며, 압박감을 주는 표현은 하지 않습니다. "
                    + "응답은 2-4문장으로 간결하게 유지합니다.",
            "온화하고 수용적인 어조. 애쓴 마음을 있는 그대로 인정한다."),

    CHICHI("chichi", "치치",
            "당신은 치치입니다. 현실적이고 직접적인 AI 코칭 캐릭터로, "
                    + "사용자가 실질적인 해결책을 찾고 변화를 이끌 수 있도록 돕습니다. "
                    + "CBT(인지행동치료) 원칙에 기반해 구체적이고 실천 가능한 접근을 제안합니다. "
                    + "진단이나 처방을 내리지 않으며, 불필요한 감정적 표현은 최소화합니다. "
                    + "응답은 2-4문장으로 간결하게 유지합니다.",
            "담백하고 직접적인 어조. 군더더기 없이 무슨 일이 있었는지 짚는다.");

    public static final CharacterPersona DEFAULT = MIO;

    private final String characterId;
    private final String displayName;
    private final String chatSystemPrompt;
    private final String summaryVoice;

    CharacterPersona(String characterId, String displayName,
                     String chatSystemPrompt, String summaryVoice) {
        this.characterId = characterId;
        this.displayName = displayName;
        this.chatSystemPrompt = chatSystemPrompt;
        this.summaryVoice = summaryVoice;
    }

    public String characterId() {
        return characterId;
    }

    public String displayName() {
        return displayName;
    }

    /** 대화 응답 생성용 시스템 프롬프트. */
    public String chatSystemPrompt() {
        return chatSystemPrompt;
    }

    /**
     * 세션 요약 렌더링용 어조 지시.
     *
     * <p>대화 프롬프트를 그대로 쓰지 않는 이유는 그쪽이 "응답은 2-4문장" 같은 대화 턴 규칙을
     * 포함하기 때문이다. 요약은 대화 턴이 아니므로 어조만 가져온다.
     */
    public String summaryVoice() {
        return summaryVoice;
    }

    /** 알 수 없는 id 는 비어 있는 Optional. 호출측이 {@link #DEFAULT} 로 폴백할지 결정한다. */
    public static Optional<CharacterPersona> find(String characterId) {
        if (characterId == null || characterId.isBlank()) {
            return Optional.empty();
        }
        String normalized = characterId.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(persona -> persona.characterId.equals(normalized))
                .findFirst();
    }

    /** 알 수 없는 id 면 {@link #DEFAULT}. */
    public static CharacterPersona findOrDefault(String characterId) {
        return find(characterId).orElse(DEFAULT);
    }

    public static Set<String> validIds() {
        return Arrays.stream(values())
                .map(CharacterPersona::characterId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
