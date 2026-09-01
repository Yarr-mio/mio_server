package com.mio.ai.memory.retrieval;

/**
 * 기억 항목의 민감도가 이번 턴의 cap 안에 드는지 판정한다 (§12.4, §12.5).
 *
 * <p>등급은 {@code normal < sensitive < restricted} 순으로 좁아지고, cap 은 "이번 턴에
 * 프롬프트로 내보낼 수 있는 최대 등급"이다. 값은 DB {@code CHECK (sensitivity IN (...))}
 * 제약(V8 · V9 · V22)과 같은 문자열을 그대로 쓴다 — 저장 형식이 계약이라 enum 으로 바꾸더라도
 * 이 리터럴은 유지돼야 한다.
 *
 * <p>이 판정은 검색 랭커와 컨텍스트 새니타이저 양쪽에서 쓰인다. 두 곳이 각자 복사본을 들고
 * 있으면 한쪽만 고쳐도 컴파일과 테스트가 통과하고, 결과는 사용자가 비공개로 둔 기억이 프롬프트에
 * 실려 나가는 <b>조용한 유출</b>이다. 호출 지점은 계층 방어를 위해 그대로 두되 판정은 여기 하나만 둔다.
 */
public final class SensitivityCap {

    public static final String NORMAL = "normal";
    public static final String SENSITIVE = "sensitive";
    public static final String RESTRICTED = "restricted";

    private SensitivityCap() {
    }

    /**
     * {@code sensitivity} 등급의 항목을 {@code cap} 아래에서 내보내도 되는지 판정한다.
     *
     * <p>{@code cap} 이 null 이면 아무것도 통과시키지 않는다 — cap 을 모르는 상태는 "제한 없음"이
     * 아니라 "판단 불가"이고, 그때 열어 주면 실패가 유출 방향으로 기운다. 호출부가 기본값을
     * 원하면 넘기기 전에 스스로 정한다.
     *
     * <p>{@code sensitivity} 가 null 이면 {@code normal} 로 본다. 저장 시 기본값이 {@code normal}
     * 이고, 등급이 비었다는 것은 분류되지 않았다는 뜻이지 민감하다는 뜻이 아니다.
     *
     * <p>모르는 cap 문자열은 가장 좁은 {@code normal} 로 해석한다. 오타가 권한 상승이 되면 안 된다.
     */
    public static boolean allows(String cap, String sensitivity) {
        if (cap == null) return false;
        String level = sensitivity != null ? sensitivity : NORMAL;
        return switch (cap) {
            case RESTRICTED -> true;
            case SENSITIVE -> !RESTRICTED.equals(level);
            default -> NORMAL.equals(level);
        };
    }
}
