package com.mio.notification.service;

public enum PushSendResult {
    SENT,
    FAILED,
    TOKEN_EXPIRED,
    INVALID_TOKEN,
    SKIPPED
}
