package com.mio.notification.service;

import java.util.UUID;

/**
 * 디바이스 토큰 1건의 발송 결과 (이슈 #497).
 *
 * <p>연속 실패 상한을 계산하려면 "이 토큰이 이번에 성공했는가 실패했는가" 가 발송 루프
 * 바깥까지 전달돼야 한다. {@code PushSendResult} 는 토큰을 모르고,
 * {@code DeviceToken} 엔티티는 발송 루프에서 detached 상태라 그대로 저장할 수 없다.
 *
 * @param tokenId       대상 토큰
 * @param sent          발송 성공 여부 — true 면 연속 실패 카운터를 초기화한다
 * @param failureReason 실패 사유. 민감 정보는 {@code PushSendResult} 단계에서 이미 걸러진다
 */
public record TokenSendOutcome(UUID tokenId, boolean sent, String failureReason) {

    public static TokenSendOutcome sent(UUID tokenId) {
        return new TokenSendOutcome(tokenId, true, null);
    }

    public static TokenSendOutcome failed(UUID tokenId, String failureReason) {
        return new TokenSendOutcome(tokenId, false, failureReason);
    }
}
