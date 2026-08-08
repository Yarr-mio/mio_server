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

    /** 토큰이 더 이상 유효하지 않아 무효화해야 하는 결과인지 여부. */
    public boolean invalidatesToken() {
        return status == PushSendStatus.TOKEN_EXPIRED || status == PushSendStatus.INVALID_TOKEN;
    }
}
