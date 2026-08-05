package com.mio.events.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.events.config.EventWhitelist;
import com.mio.events.dto.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 전용 로거 "journey-events"에 순수 JSON 한 줄을 남긴다 — logback-spring.xml에서
 * additivity=false로 콘솔/앱 일반 로그(#281, /mio/app)와 물리적으로 분리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventLogWriter {

    private static final Logger JOURNEY_LOG = LoggerFactory.getLogger("journey-events");
    private static final Logger JOURNEY_REJECTED_LOG = LoggerFactory.getLogger("journey-events-rejected");

    private final EventWhitelist eventWhitelist;
    private final ObjectMapper objectMapper;

    public void write(EventEnvelope event, String requestId) {
        // #320 — 키만 보면 enum 자리에 자유 문자열이 실려도 통과했다(예: employment_status).
        // 값이 도메인 밖이면 그 property만 드롭한다 — 이벤트 자체는 통과시킨다.
        // #326 — drop(카탈로그에 없는 키)과 invalid(아는 키인데 값이 도메인 밖)를 서로 다른
        // 로그로 남긴다. 둘 다 조용히 사라지면 클라이언트 계측 결함을 감지할 방법이 없다.
        Map<String, Object> filteredProperties = new LinkedHashMap<>();
        if (event.properties() != null) {
            event.properties().forEach((key, value) -> {
                if (!eventWhitelist.isKnownProperty(event.eventName(), key)) {
                    log.warn("journey_property_dropped event_name={} key={}", event.eventName(), key);
                    return;
                }
                if (!eventWhitelist.isValidPropertyValue(event.eventName(), key, value)) {
                    log.warn("journey_property_invalid event_name={} key={}", event.eventName(), key);
                    return;
                }
                filteredProperties.put(key, value);
            });
        }

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event_id", event.eventId());
        line.put("event_name", event.eventName());
        line.put("schema_version", event.schemaVersion());
        line.put("ts_client", event.tsClient());
        line.put("ts_server", Instant.now().toString());
        line.put("request_id", requestId);
        line.put("anonymous_id", event.anonymousId());
        line.put("user_id", event.userId());
        line.put("app_session_id", event.appSessionId());
        line.put("app_version", event.appVersion());
        line.put("platform", event.platform());
        line.put("os_version", event.osVersion());
        line.put("properties", filteredProperties);

        try {
            JOURNEY_LOG.info(objectMapper.writeValueAsString(line));
        } catch (JsonProcessingException e) {
            // 이벤트 로그 기록 실패는 통계적 손실일 뿐 — 요청 자체를 막지 않는다 (감사로그와 다른 성격)
            log.error("Failed to serialize journey event: event_id={}", event.eventId(), e);
        }
    }

    /**
     * 이슈 #326 — 거부된 이벤트는 지금까지 {@code journey_event_rejected} 경고 로그 한 줄만
     * 남고 원본 payload가 사라졌다. 사후에 "어떤 속성이 왜 거부됐는지" 확인할 수 있도록
     * 원본 그대로 전용 로거("journey-events-rejected")에 남긴다 — journey-events와 물리적으로
     * 분리해 정상 지표 집계 파이프라인을 오염시키지 않는다.
     */
    public void writeRejected(EventEnvelope event, int index, String reason, String requestId) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("index", index);
        line.put("reject_reason", reason);
        line.put("event_id", event.eventId());
        line.put("event_name", event.eventName());
        line.put("schema_version", event.schemaVersion());
        line.put("ts_client", event.tsClient());
        line.put("ts_server", Instant.now().toString());
        line.put("request_id", requestId);
        line.put("anonymous_id", event.anonymousId());
        line.put("user_id", event.userId());
        line.put("app_session_id", event.appSessionId());
        line.put("app_version", event.appVersion());
        line.put("platform", event.platform());
        line.put("os_version", event.osVersion());
        line.put("properties", event.properties());

        try {
            JOURNEY_REJECTED_LOG.info(objectMapper.writeValueAsString(line));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize rejected journey event: event_id={}", event.eventId(), e);
        }
    }
}
