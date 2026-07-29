package com.mio.session.domain;

import java.util.Arrays;

/**
 * 한 번의 메시지 턴이 어디까지 갔는지 (docs/백엔드 문서/12_안전_신뢰성_전수조사_2026-07.md §5 P0-A).
 *
 * <p>{@code generating} 에 머문 턴은 응답을 만들지 못하고 끝난 것이다. 어떤 종료 경로에서도
 * 터미널 상태({@code completed}/{@code failed})가 저장되어야, 같은 Idempotency 키로 재시도했을 때
 * 서버가 결말을 알고 응답할 수 있다.
 */
public enum TurnStatus {

    /** 사용자 발화까지 저장됐고 응답 생성이 진행 중. */
    GENERATING("generating"),

    /** 응답까지 저장 완료. */
    COMPLETED("completed"),

    /** 응답을 만들지 못하고 종료. 사유는 {@code finished_reason} 에 남는다. */
    FAILED("failed");

    private final String value;

    TurnStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isTerminal() {
        return this != GENERATING;
    }

    public static TurnStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(s -> s.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown turn status: " + value));
    }
}
