package com.mio.character.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 캐릭터별 인사 카피의 단일 출처 (이슈 #530, 원본 추적 #428).
 *
 * <p>인사말은 세션 개시(선제 인사)와 캐릭터 변경 응답 두 곳에서 쓰인다. 서비스마다 상수를
 * 따로 두면 같은 캐릭터가 화면마다 다른 말투로 인사하게 되므로 여기 하나만 둔다.
 *
 * <p>LLM 을 쓰지 않는다. 사용자 발화 전에는 안전·CBT 추론을 실행할 근거가 없어서, 모델이
 * 감정이나 과거를 추정하면 사용자가 하지 않은 말을 사실처럼 꺼내게 된다.
 *
 * <p><b>두 가지 모드</b> — 처음 만나는 사용자에게는 자기소개를 하고("난 미오야"), 다시 온
 * 사용자에게는 닉네임을 부른다("안녕 민석!"). 매번 자기를 소개하면 계속 처음 만난 사이처럼
 * 느껴지고, 첫 만남에 이름부터 부르면 소개 없이 아는 척하는 셈이 된다.
 *
 * <p>문구 계약 — 1~2문장, 질문 최대 1개, 진단·위기 단정·과거 기억 단정 금지, 의존성을
 * 강화하는 표현("항상 곁에 있다") 금지, 행동 과제·Todo·CBT 재구성 금지. 캐릭터 차이는
 * 어조에만 두고 안전 기준은 동일하다.
 *
 * <p>모드별로 캐릭터당 3종을 두는 이유: 1종이면 사용자가 새 채팅마다 완전히 같은 문장을 본다.
 */
public final class OpeningMessageCatalog {

    /** 카탈로그에 없는 캐릭터가 들어왔을 때 쓰는 폴백. 인사가 안 나가는 것보다 낫다. */
    public static final String FALLBACK_CHARACTER_ID = "mio";

    /** 닉네임 치환 슬롯. */
    public static final String NICKNAME_PLACEHOLDER = "{nickname}";

    /**
     * 캐릭터 노출 순서. {@code Map.copyOf} 는 반복 순서를 보장하지 않아 별도로 둔다 —
     * 카탈로그 순서가 실행마다 흔들리면 검증·문서 대조가 재현되지 않는다.
     */
    private static final List<String> CHARACTER_ORDER = List.of("mio", "bau", "rumi", "momo", "chichi");

    private static final Map<String, Map<OpeningAudience, List<OpeningMessage>>> BY_CHARACTER;

    static {
        Map<String, Map<OpeningAudience, List<OpeningMessage>>> map = new LinkedHashMap<>();
        map.put("mio", sets(
                List.of(
                        new OpeningMessage("mio_intro_01", "안녕! 난 미오야 🐧 오늘 어떤 하루를 보냈어?"),
                        new OpeningMessage("mio_intro_02", "반가워, 난 미오야 🐧 지금 기분은 어때?"),
                        new OpeningMessage("mio_intro_03", "안녕! 난 미오야 🐧 편하게 아무 얘기부터 시작해도 돼.")
                ),
                List.of(
                        new OpeningMessage("mio_back_01", "안녕 {nickname}! 🐧 오늘 어떤 하루를 보냈어?"),
                        new OpeningMessage("mio_back_02", "{nickname}, 왔네! 🐧 지금 기분은 어때?"),
                        new OpeningMessage("mio_back_03", "안녕 {nickname}! 🐧 오늘은 무슨 얘기부터 해볼까?")
                )));
        map.put("bau", sets(
                List.of(
                        new OpeningMessage("bau_intro_01", "안녕! 난 바우야 🐕 오늘 뭘 해봤어?"),
                        new OpeningMessage("bau_intro_02", "반가워! 난 바우야 🐕 오늘은 어떻게 지냈어?"),
                        new OpeningMessage("bau_intro_03", "안녕! 난 바우야 🐕 오늘 이야기 하나만 들려줘.")
                ),
                List.of(
                        new OpeningMessage("bau_back_01", "안녕 {nickname}! 🐕 오늘 뭘 해봤어?"),
                        new OpeningMessage("bau_back_02", "{nickname}, 왔구나! 🐕 오늘은 어떻게 지냈어?"),
                        new OpeningMessage("bau_back_03", "안녕 {nickname}! 🐕 오늘 이야기 하나만 들려줘.")
                )));
        map.put("rumi", sets(
                List.of(
                        new OpeningMessage("rumi_intro_01", "안녕, 난 루미야 🦉 무엇이 너를 괴롭히고 있어?"),
                        new OpeningMessage("rumi_intro_02", "안녕, 난 루미야 🦉 지금 가장 많이 떠오르는 건 뭐야?"),
                        new OpeningMessage("rumi_intro_03", "안녕, 난 루미야 🦉 순서는 상관없어. 떠오르는 것부터 말해 줘.")
                ),
                List.of(
                        new OpeningMessage("rumi_back_01", "안녕 {nickname} 🦉 무엇이 너를 괴롭히고 있어?"),
                        new OpeningMessage("rumi_back_02", "{nickname}, 왔네 🦉 지금 가장 많이 떠오르는 건 뭐야?"),
                        new OpeningMessage("rumi_back_03", "안녕 {nickname} 🦉 순서는 상관없어. 떠오르는 것부터 말해 줘.")
                )));
        map.put("momo", sets(
                List.of(
                        new OpeningMessage("momo_intro_01", "안녕... 난 모모야 🐻 오늘 어떤 하루였어?"),
                        new OpeningMessage("momo_intro_02", "반가워... 난 모모야 🐻 지금 마음은 어때?"),
                        new OpeningMessage("momo_intro_03", "안녕... 난 모모야 🐻 말하고 싶은 만큼만 말해도 괜찮아.")
                ),
                List.of(
                        new OpeningMessage("momo_back_01", "안녕 {nickname}... 🐻 오늘 어떤 하루였어?"),
                        new OpeningMessage("momo_back_02", "{nickname}, 왔구나... 🐻 지금 마음은 어때?"),
                        new OpeningMessage("momo_back_03", "안녕 {nickname}... 🐻 말하고 싶은 만큼만 말해도 괜찮아.")
                )));
        map.put("chichi", sets(
                List.of(
                        new OpeningMessage("chichi_intro_01", "안녕, 난 치치야 😺 뭐가 문제야, 말해봐."),
                        new OpeningMessage("chichi_intro_02", "난 치치야 😺 오늘 제일 걸리는 게 뭐야?"),
                        new OpeningMessage("chichi_intro_03", "난 치치야 😺 하나만 골라서 말해봐. 거기서 시작하자.")
                ),
                List.of(
                        new OpeningMessage("chichi_back_01", "{nickname}, 뭐가 문제야 😺 말해봐."),
                        new OpeningMessage("chichi_back_02", "어서 와 {nickname} 😺 오늘 제일 걸리는 게 뭐야?"),
                        new OpeningMessage("chichi_back_03", "{nickname}, 하나만 골라서 말해봐 😺 거기서 시작하자.")
                )));
        BY_CHARACTER = Map.copyOf(map);
    }

    private OpeningMessageCatalog() {
    }

    /**
     * 해당 캐릭터·모드의 문구 후보 전체. 알 수 없는 캐릭터면 {@code mio} 세트로 폴백한다.
     *
     * <p>{@code character_id} 유효성은 이미 세션·캐릭터 서비스가 앞단에서 검증한다. 여기서
     * 예외를 던지면 배포 결함(카피 누락) 하나가 세션 생성 전체를 막는다.
     */
    public static List<OpeningMessage> messagesFor(String characterId, OpeningAudience audience) {
        Map<OpeningAudience, List<OpeningMessage>> sets = BY_CHARACTER.get(characterId);
        if (sets == null) {
            sets = BY_CHARACTER.get(FALLBACK_CHARACTER_ID);
        }
        return sets.get(audience);
    }

    /**
     * 캐릭터 대표 문구 — 캐릭터 변경 응답의 {@code greeting_message} 가 쓴다.
     *
     * <p>자기소개 세트의 첫 문구를 쓴다. 이 엔드포인트는 닉네임을 다루지 않고, 로테이션도
     * 적용하지 않는다 — 호출마다 값이 달라지면 기존 API 의 동작이 바뀐다.
     */
    public static String representativeMessage(String characterId) {
        return messagesFor(characterId, OpeningAudience.FIRST_SESSION).get(0).content();
    }

    /** 카탈로그가 문구를 가진 캐릭터 ID 목록 (노출 순서). 테스트·검증용. */
    public static List<String> characterIds() {
        return CHARACTER_ORDER;
    }

    private static Map<OpeningAudience, List<OpeningMessage>> sets(List<OpeningMessage> firstSession,
                                                                   List<OpeningMessage> returning) {
        return Map.of(
                OpeningAudience.FIRST_SESSION, firstSession,
                OpeningAudience.RETURNING, returning);
    }

    /** 인사 대상. 사용자가 이 캐릭터 서비스를 처음 쓰는지 여부로 갈린다. */
    public enum OpeningAudience {

        /** 첫 세션 — 자기소개를 한다. 닉네임을 쓰지 않으므로 닉네임이 없어도 안전하다. */
        FIRST_SESSION,

        /** 재방문 — 닉네임을 부른다. 유효한 닉네임이 있을 때만 선택한다. */
        RETURNING
    }

    /**
     * 검수된 인사 문구 한 건.
     *
     * @param variant 로테이션 식별자 {@code {characterId}_{intro|back}_NN}. DB 와 지표에만 쓰고
     *                API 로 노출하지 않는다 — 노출하면 클라이언트가 문구로 분기할 여지가 생긴다.
     * @param content 슬롯이 치환되지 않은 원본 템플릿
     */
    public record OpeningMessage(String variant, String content) {

        /** 닉네임 슬롯을 가진 문구인지. */
        public boolean requiresNickname() {
            return content.contains(NICKNAME_PLACEHOLDER);
        }

        /**
         * 닉네임을 넣어 렌더링한다.
         *
         * <p>슬롯이 있는 문구에 닉네임이 없으면 슬롯이 그대로 사용자에게 보이게 되므로
         * 예외로 막는다. 선택 단계에서 닉네임 없는 사용자에게는 자기소개 세트를 주도록
         * 되어 있어, 이 예외는 그 규칙이 깨졌다는 신호다.
         */
        public String render(String nickname) {
            if (!requiresNickname()) {
                return content;
            }
            if (nickname == null || nickname.isBlank()) {
                throw new IllegalArgumentException("Nickname required for opening variant: " + variant);
            }
            return content.replace(NICKNAME_PLACEHOLDER, nickname);
        }
    }
}
