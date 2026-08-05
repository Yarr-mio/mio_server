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

    private final EventWhitelist eventWhitelist;
    private final ObjectMapper objectMapper;

    public void write(EventEnvelope event, String requestId) {
        // #320 — 키만 보면 enum 자리에 자유 문자열이 실려도 통과했다(예: employment_status).
        // 값이 도메인 밖이면 그 property만 드롭한다 — 이벤트 자체는 통과시킨다.
        Map<String, Object> filteredProperties = new LinkedHashMap<>();
        if (event.properties() != null) {
            event.properties().forEach((key, value) -> {
                if (eventWhitelist.isValidPropertyValue(event.eventName(), key, value)) {
                    filteredProperties.put(key, value);
                }
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
}
