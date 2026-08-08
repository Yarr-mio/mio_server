package com.mio.notification.service;

/** 디바이스 토큰 1건에 대한 푸시 발송 결과 상태. */
public enum PushSendStatus {
    SENT,
    FAILED,
    TOKEN_EXPIRED,
    INVALID_TOKEN,
    SKIPPED
}
