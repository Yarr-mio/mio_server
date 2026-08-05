package com.mio.events.service;

/** 이슈 #285 — 강민석 인프라 명세 §4-H가 이 이름을 CloudWatch 메트릭 필터로 센다. */
public enum EventRejectionReason {
    MISSING_EVENT_ID,
    MISSING_EVENT_NAME,
    MISSING_TS_CLIENT,
    MISSING_ANONYMOUS_ID,
    MISSING_APP_SESSION_ID,
    TS_CLIENT_INVALID_FORMAT,
    UNKNOWN_EVENT_NAME,
    /** 이슈 #324 — 거부가 아니라 통지. 지표는 투영 dedup으로 안전하다. */
    DUPLICATE_IN_BATCH
}
