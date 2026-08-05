package com.mio.events.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 강민석 "mio 이벤트 로그 명세 v3.4" §2 공통 envelope. 전역 snake_case 전략이 없는 프로젝트라
 * 필드마다 {@code @JsonProperty}를 명시한다 (LoginRequest와 동일 컨벤션).
 */
public record EventEnvelope(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_name") String eventName,
        @JsonProperty("schema_version") Integer schemaVersion,
        @JsonProperty("ts_client") String tsClient,
        @JsonProperty("anonymous_id") String anonymousId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("app_session_id") String appSessionId,
        @JsonProperty("app_version") String appVersion,
        String platform,
        @JsonProperty("os_version") String osVersion,
        Map<String, Object> properties
) {

    /** 이슈 #324 — Authorization 토큰의 sub와 대조해 서버 값으로 덮어쓸 때 쓴다. */
    public EventEnvelope withUserId(String newUserId) {
        return new EventEnvelope(eventId, eventName, schemaVersion, tsClient, anonymousId, newUserId,
                appSessionId, appVersion, platform, osVersion, properties);
    }
}
