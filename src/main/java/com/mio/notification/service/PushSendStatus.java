package com.mio.notification.service;

/**
 * 디바이스 토큰 1건에 대한 푸시 발송 결과 상태.
 *
 * <p>{@link #FAILED}·{@link #TOKEN_EXPIRED}·{@link #INVALID_TOKEN} 은 게이트웨이가 명시적으로
 * 거절한 것이므로 <b>확실히 미발송</b>이다. 반면 {@link #AMBIGUOUS} 는 요청을 보냈지만 응답을
 * 받지 못한 상태라 발송 여부를 알 수 없다. 이 구분이 재발송 판단의 근거가 된다.
 */
public enum PushSendStatus {
    SENT,
    /** 게이트웨이가 명시적으로 거절 — 확실히 미발송. 재시도해도 안전하다. */
    FAILED,
    TOKEN_EXPIRED,
    INVALID_TOKEN,
    /**
     * 발송 여부 불명 — 네트워크 타임아웃 등으로 응답을 받지 못했다.
     * 게이트웨이가 이미 처리했을 수 있으므로 재시도하면 유저 기기에 푸시가 두 번 도착한다.
     */
    AMBIGUOUS,
    SKIPPED
}
