package com.mio.events.service;

/** 이슈 #285 — 강민석 인프라 명세 §4-H가 이 이름을 CloudWatch 메트릭 필터로 센다. */
public enum EventRejectionReason {
    MISSING_EVENT_ID,
    MISSING_EVENT_NAME,
    MISSING_TS_CLIENT,
    MISSING_ANONYMOUS_ID,
    MISSING_APP_SESSION_ID,
    INVALID_SCHEMA_VERSION,
    TS_CLIENT_INVALID_FORMAT,
    UNKNOWN_EVENT_NAME
}
