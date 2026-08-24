package com.mio.session.domain;

import java.util.Arrays;

/**
 * 메시지 종류 (이슈 #530).
 *
 * <p>선제 인사는 사용자 발화에 대한 응답이 아니라 세션 개시 시 서버가 저장하는 고정 문구다.
 * 일반 대화와 섞이면 (1) 세션당 1건 제약을 걸 수 없고, (2) 사용자 발화 없이 오프닝만 있는
 * 세션을 정상 대화로 오인해 한 문장으로 요약·Todo 를 만들게 된다.
 */
public enum MessageKind {

    /** 일반 대화 — 사용자 발화와 그에 대한 AI 응답. */
    CONVERSATION("conversation"),

    /** 세션 생성 시 저장하는 선제 인사. {@code role=assistant} 만 허용, 세션당 최대 1건. */
    SESSION_OPENING("session_opening");

    private final String value;

    MessageKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MessageKind fromValue(String value) {
        return Arrays.stream(values())
                .filter(kind -> kind.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown message kind: " + value));
    }
}
