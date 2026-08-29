package com.mio.notification.service;

/**
 * 푸시 발송 결과. 실패 시 사후 추적을 위해 사유(APNs HTTP 상태·reason, FCM 오류 코드)를 함께 싣는다.
 * 사유에는 디바이스 토큰 등 민감 정보를 담지 않는다.
 */
public record PushSendResult(PushSendStatus status, String failureReason) {

    private static final int MAX_FAILURE_REASON_LENGTH = 200;

    public PushSendResult {
        if (failureReason != null && failureReason.length() > MAX_FAILURE_REASON_LENGTH) {
            failureReason = failureReason.substring(0, MAX_FAILURE_REASON_LENGTH);
        }
    }

    public static PushSendResult sent() {
        return new PushSendResult(PushSendStatus.SENT, null);
    }

    public static PushSendResult of(PushSendStatus status, String failureReason) {
        return new PushSendResult(status, failureReason);
    }

    public boolean isSent() {
        return status == PushSendStatus.SENT;
    }

    /** 발송 여부를 알 수 없는 결과인지 — 재시도하면 중복 발송이 될 수 있다. */
    public boolean isAmbiguous() {
        return status == PushSendStatus.AMBIGUOUS;
    }

    /** 토큰이 더 이상 유효하지 않아 무효화해야 하는 결과인지 여부. */
    public boolean invalidatesToken() {
        return status == PushSendStatus.TOKEN_EXPIRED || status == PushSendStatus.INVALID_TOKEN;
    }

    /**
     * 연속 실패 상한(이슈 #497)에 반영할 결과인지.
     *
     * <p>{@link PushSendStatus#FAILED} 만 센다. 나머지는 세면 안 되는 이유가 각각 다르다.
     * <ul>
     *   <li>{@code AMBIGUOUS} — 발송됐을 수도 있다. 실패로 세면 <b>정상 동작하는 토큰이</b>
     *       네트워크 문제만으로 발송 대상에서 빠진다</li>
     *   <li>{@code SKIPPED} — APNs·FCM 설정이 꺼져 있는 것이라 토큰 문제가 아니다.
     *       설정이 빠진 환경에서 전 토큰이 상한에 도달해 버린다</li>
     *   <li>{@code TOKEN_EXPIRED}·{@code INVALID_TOKEN} — 이미 무효화되어 발송 대상에서
     *       빠지므로 카운터를 올릴 이유가 없다</li>
     * </ul>
     */
    public boolean countsTowardFailureCap() {
        return status == PushSendStatus.FAILED;
    }
}
