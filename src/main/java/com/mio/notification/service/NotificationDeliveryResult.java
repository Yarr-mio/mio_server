package com.mio.notification.service;

import com.mio.notification.domain.ProactiveCareLog;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 한 유저·한 트리거에 대한 최종 발송 결과. {@code proactive_care_logs} 에 기록할
 * {@code notification_status} 와 실패 사유를 담는다.
 */
public record NotificationDeliveryResult(String status, String failureReason) {

    private static final int MAX_FAILURE_REASON_LENGTH = 500;
    private static final String REASON_DELIMITER = "; ";
    private static final String NO_DEVICE_REASON = "NO_VALID_DEVICE_TOKEN";

    public NotificationDeliveryResult {
        if (failureReason != null && failureReason.length() > MAX_FAILURE_REASON_LENGTH) {
            failureReason = failureReason.substring(0, MAX_FAILURE_REASON_LENGTH);
        }
    }

    /** 모든 단말에 발송이 성공한 경우. */
    public static NotificationDeliveryResult sent() {
        return sent(List.of());
    }

    /**
     * 최소 1개 단말에 발송이 성공한 경우. 일부 단말이 실패했다면 그 사유도 함께 남긴다 —
     * iOS 는 실패하고 Android 만 성공한 상황을 사후에 추적할 수 있어야 한다.
     */
    public static NotificationDeliveryResult sent(List<String> partialFailureReasons) {
        return new NotificationDeliveryResult(ProactiveCareLog.STATUS_SENT, joinReasons(partialFailureReasons));
    }

    /** 보낼 유효 디바이스 토큰이 없어 발송을 시도조차 하지 않은 경우. */
    public static NotificationDeliveryResult noDevice() {
        return new NotificationDeliveryResult(ProactiveCareLog.STATUS_NO_DEVICE, NO_DEVICE_REASON);
    }

    /** 발송을 시도했고 모든 단말이 게이트웨이에서 <b>명시적으로 거절</b>된 경우 — 확실히 미발송. */
    public static NotificationDeliveryResult failed(List<String> reasons) {
        return new NotificationDeliveryResult(ProactiveCareLog.STATUS_FAILED, joinReasons(reasons));
    }

    /**
     * 성공한 단말이 없고, 최소 1개 단말의 결과가 불명(타임아웃 등)인 경우.
     * 이미 발송됐을 수 있으므로 재발송 억제 대상이지만, 일일 한도에는 넣지 않는다.
     */
    public static NotificationDeliveryResult unconfirmed(List<String> reasons) {
        return new NotificationDeliveryResult(ProactiveCareLog.STATUS_UNCONFIRMED, joinReasons(reasons));
    }

    /** 사유는 중복을 제거해 합친다. 남길 사유가 없으면 null. */
    private static String joinReasons(List<String> reasons) {
        String joined = reasons.stream()
                .filter(Objects::nonNull)
                .filter(reason -> !reason.isBlank())
                .distinct()
                .collect(Collectors.joining(REASON_DELIMITER));
        return joined.isBlank() ? null : joined;
    }

    /**
     * 실제로 단말까지 발송된 결과인지 여부 — 일일 한도 차감의 기준이 된다.
     *
     * <p>{@code SENT} 만 검사하는 것으로 충분하다. 이 record 는 신규 기록 시점의 결과만 표현하며,
     * 그 시점에 {@code DELIVERED}·{@code OPENED} 는 나올 수 없다. 이미 저장된 행을 대상으로 하는
     * 판정은 {@link ProactiveCareLog#DELIVERED_STATUSES} 를 쓴다.
     */
    public boolean isDelivered() {
        return ProactiveCareLog.STATUS_SENT.equals(status);
    }
}
