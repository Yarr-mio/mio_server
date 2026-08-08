package com.mio.notification.dto;

import com.mio.notification.domain.ProactiveCareLog;

import java.util.Set;

/**
 * {@code notification_status} 의 내부값 → 노출값 변환. API 경계의 유일한 매핑 지점이다.
 *
 * <p>내부적으로는 {@link ProactiveCareLog#STATUS_NO_DEVICE}(보낼 단말 없음)와
 * {@link ProactiveCareLog#STATUS_UNCONFIRMED}(발송 여부 불명)를 {@code FAILED} 와 구분해서 저장한다.
 * 발송을 시도해서 거절당한 것, 시도조차 못 한 것, 응답을 못 받은 것은 원인이 다르고, 재발송 억제·
 * 일일 한도·도달률 지표가 이 구분에 의존한다 (이슈 #387, #389, #390).
 *
 * <p>반면 API 명세({@code 10_Notification_알림.md} §알림 수신 상태, {@code State_공통상태값정의.md} §19)는
 * {@code SENT / DELIVERED / OPENED / FAILED} 4종으로 고정돼 있고 FE 가 이 값으로 분기한다
 * (명세: "{@code notification_status: FAILED} 항목은 이력 화면에서 재시도 불가 안내 UI 처리 권장").
 * 문서에 없는 값을 내보내면 FE 분기가 깨지므로, 응답에서는 의미가 가장 가까운 {@code FAILED}
 * ("FCM 토큰 만료 또는 전송 오류")로 접어서 내보낸다.
 *
 * <p>즉 구분은 DB·내부 로직에만 남기고, 계약은 그대로 지킨다. 새 상태값을 FE 에 노출하려면
 * 명세와 공통 상태값 정의를 먼저 개정해야 한다.
 */
final class NotificationStatusView {

    /** 명세에 없는 내부 전용 상태 — 응답에서는 모두 {@code FAILED} 로 접는다. */
    private static final Set<String> INTERNAL_ONLY_STATUSES = Set.of(
            ProactiveCareLog.STATUS_NO_DEVICE,
            ProactiveCareLog.STATUS_UNCONFIRMED
    );

    private NotificationStatusView() {
    }

    static String of(String internalStatus) {
        return INTERNAL_ONLY_STATUSES.contains(internalStatus)
                ? ProactiveCareLog.STATUS_FAILED
                : internalStatus;
    }
}
