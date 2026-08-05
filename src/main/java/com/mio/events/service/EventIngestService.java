package com.mio.events.service;

import com.mio.events.config.EventSchemaProperties;
import com.mio.events.config.EventWhitelist;
import com.mio.events.dto.EventEnvelope;
import com.mio.events.dto.EventsIngestResponse;
import com.mio.events.dto.RejectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * POST /v1/events 배치 검증 + 기록 (이슈 #285). 부분 성공 모델 — 개별 이벤트 검증 실패는
 * 배치 전체를 막지 않고 거부 목록에만 담는다 (강민석 명세 §5 ③).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventIngestService {

    private final EventWhitelist eventWhitelist;
    private final EventLogWriter eventLogWriter;
    private final EventSchemaProperties eventSchemaProperties;

    public EventsIngestResponse ingest(List<EventEnvelope> events, String requestId) {
        List<RejectedEvent> rejected = new ArrayList<>();
        Set<String> seenEventIds = new HashSet<>();
        int acceptedCount = 0;

        for (int index = 0; index < events.size(); index++) {
            EventEnvelope event = events.get(index);
            EventRejectionReason reason = validate(event);
            if (reason != null) {
                rejected.add(new RejectedEvent(index, event.eventId(), event.eventName(), reason.name()));
                log.warn("journey_event_rejected reason={} event_name={}", reason, event.eventName());
                eventLogWriter.writeRejected(event, index, reason.name(), requestId);
                continue;
            }
            // 이슈 #324 — 배치 안 event_id 중복은 거부가 아니라 통지다. 지표는 투영
            // dedup으로 안전하지만, 통지 없이 그대로 적재하면 "이벤트 수신 건수" 알람이
            // 부풀어 계측 생존 판정이 낙관 편향된다.
            if (!seenEventIds.add(event.eventId())) {
                rejected.add(new RejectedEvent(index, event.eventId(), event.eventName(),
                        EventRejectionReason.DUPLICATE_IN_BATCH.name()));
                continue;
            }
            // 이슈 #322 — 앱 버전은 항상 혼재한다. schema_version이 허용 집합 밖이어도
            // 거부하지 않는다 — 거부하면 업데이트가 늦은(=앱을 잘 안 여는=리텐션이 나쁜)
            // 유저의 이벤트가 선택적으로 사라진다. 태깅만 하고 그대로 적재한다.
            if (event.schemaVersion() == null
                    || !eventSchemaProperties.getAcceptedSchemaVersions().contains(event.schemaVersion())) {
                log.warn("journey_event_schema_mismatch version={}", event.schemaVersion());
            }
            eventLogWriter.write(event, requestId);
            acceptedCount++;
        }

        return new EventsIngestResponse(acceptedCount, rejected);
    }

    private EventRejectionReason validate(EventEnvelope event) {
        if (isBlank(event.eventId())) return EventRejectionReason.MISSING_EVENT_ID;
        if (isBlank(event.eventName())) return EventRejectionReason.MISSING_EVENT_NAME;
        if (isBlank(event.tsClient())) return EventRejectionReason.MISSING_TS_CLIENT;
        if (isBlank(event.anonymousId())) return EventRejectionReason.MISSING_ANONYMOUS_ID;
        if (isBlank(event.appSessionId())) return EventRejectionReason.MISSING_APP_SESSION_ID;
        if (!isValidIso8601(event.tsClient())) return EventRejectionReason.TS_CLIENT_INVALID_FORMAT;
        if (!eventWhitelist.isKnownEvent(event.eventName())) return EventRejectionReason.UNKNOWN_EVENT_NAME;
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isValidIso8601(String tsClient) {
        try {
            OffsetDateTime.parse(tsClient);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
