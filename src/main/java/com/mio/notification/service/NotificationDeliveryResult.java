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

    /** 최소 1개 단말에 발송이 성공한 경우. */
    public static NotificationDeliveryResult sent() {
        return new NotificationDeliveryResult(ProactiveCareLog.STATUS_SENT, null);
    }

    /** 보낼 유효 디바이스 토큰이 없어 발송을 시도조차 하지 않은 경우. */
    public static NotificationDeliveryResult noDevice() {
        return new NotificationDeliveryResult(ProactiveCareLog.STATUS_NO_DEVICE, NO_DEVICE_REASON);
    }

    /** 발송을 시도했으나 모든 단말에서 실패한 경우. 사유는 중복을 제거해 합친다. */
    public static NotificationDeliveryResult failed(List<String> reasons) {
        String joined = reasons.stream()
                .filter(Objects::nonNull)
                .filter(reason -> !reason.isBlank())
                .distinct()
                .collect(Collectors.joining(REASON_DELIMITER));
        return new NotificationDeliveryResult(
                ProactiveCareLog.STATUS_FAILED,
                joined.isBlank() ? null : joined
        );
    }

    /** 실제로 단말까지 발송된 결과인지 여부 — 일일 한도 차감·재발송 억제의 기준이 된다. */
    public boolean isDelivered() {
        return ProactiveCareLog.STATUS_SENT.equals(status);
    }
}
