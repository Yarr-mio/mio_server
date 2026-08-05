package com.mio.events.service;

import com.mio.events.config.EventSchemaProperties;
import com.mio.events.config.EventWhitelist;
import com.mio.events.dto.EventEnvelope;
import com.mio.events.dto.EventsIngestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventIngestServiceTest {

    @Mock private EventWhitelist eventWhitelist;
    @Mock private EventLogWriter eventLogWriter;

    private EventIngestService eventIngestService;

    private static final String VALID_TS = "2026-08-04T21:13:02+09:00";

    @BeforeEach
    void setUp() {
        eventIngestService = new EventIngestService(eventWhitelist, eventLogWriter, new EventSchemaProperties());
    }

    private EventEnvelope validEvent(String eventId) {
        return new EventEnvelope(eventId, "chat_message_sent", 3, VALID_TS,
                "anon-1", "user-1", "session-1", "1.0.0", "ios", "17.0", Map.of());
    }

    @Test
    @DisplayName("유효한 이벤트는 기록되고 accepted_count에 반영된다")
    void ingest_validEvent_accepted() {
        when(eventWhitelist.isKnownEvent("chat_message_sent")).thenReturn(true);

        EventsIngestResponse response = eventIngestService.ingest(List.of(validEvent("e1")), "req-1");

        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.rejected()).isEmpty();
        verify(eventLogWriter).write(any(), eq("req-1"));
    }

    @Test
    @DisplayName("카탈로그에 없는 event_name은 UNKNOWN_EVENT_NAME으로 거부된다")
    void ingest_unknownEventName_rejected() {
        when(eventWhitelist.isKnownEvent("chat_message_sent")).thenReturn(false);

        EventsIngestResponse response = eventIngestService.ingest(List.of(validEvent("e1")), "req-1");

        assertThat(response.acceptedCount()).isZero();
        assertThat(response.rejected()).hasSize(1);
        assertThat(response.rejected().get(0).reason()).isEqualTo("UNKNOWN_EVENT_NAME");
        verify(eventLogWriter, never()).write(any(), any());
    }

    @Test
    @DisplayName("#326 — 거부된 이벤트는 원본 payload가 index·reason과 함께 rejected 전용 로거로 넘어간다")
    void ingest_rejectedEvent_writesToRejectedLogger() {
        when(eventWhitelist.isKnownEvent("chat_message_sent")).thenReturn(false);
        EventEnvelope event = validEvent("e1");

        eventIngestService.ingest(List.of(event), "req-1");

        verify(eventLogWriter).writeRejected(event, 0, "UNKNOWN_EVENT_NAME", "req-1");
    }

    @Test
    @DisplayName("event_id 누락은 MISSING_EVENT_ID로 거부되고 화이트리스트 조회까지 안 간다")
    void ingest_missingEventId_rejected() {
        EventEnvelope event = new EventEnvelope(null, "chat_message_sent", 3, VALID_TS,
                "anon-1", "user-1", "session-1", "1.0.0", "ios", "17.0", Map.of());

        EventsIngestResponse response = eventIngestService.ingest(List.of(event), "req-1");

        assertThat(response.rejected().get(0).reason()).isEqualTo("MISSING_EVENT_ID");
        verifyNoInteractions(eventWhitelist);
    }

    @Test
    @DisplayName("#324 — rejected 항목에 배치 내 index가 정확히 포함된다")
    void ingest_rejectedEvent_includesBatchIndex() {
        when(eventWhitelist.isKnownEvent("chat_message_sent")).thenReturn(true);
        EventEnvelope valid = validEvent("e0");
        EventEnvelope missingId = new EventEnvelope(null, "chat_message_sent", 3, VALID_TS,
                "anon-1", "user-1", "session-1", "1.0.0", "ios", "17.0", Map.of());

        EventsIngestResponse response = eventIngestService.ingest(List.of(valid, missingId), "req-1");

        assertThat(response.rejected().get(0).index()).isEqualTo(1);
    }

    @Test
    @DisplayName("#324 — 배치 내 event_id 중복은 첫 건만 기록되고 DUPLICATE_IN_BATCH로 통지된다")
    void ingest_duplicateEventIdInBatch_secondNotified() {
        when(eventWhitelist.isKnownEvent("chat_message_sent")).thenReturn(true);
        EventEnvelope first = validEvent("dup-1");
        EventEnvelope duplicate = validEvent("dup-1");

        EventsIngestResponse response = eventIngestService.ingest(List.of(first, duplicate), "req-1");

        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.rejected()).hasSize(1);
        assertThat(response.rejected().get(0).index()).isEqualTo(1);
        assertThat(response.rejected().get(0).reason()).isEqualTo("DUPLICATE_IN_BATCH");
        verify(eventLogWriter, times(1)).write(any(), eq("req-1"));
    }

    @Test
    @DisplayName("#322 — schema_version이 허용 집합 밖이어도 거부하지 않고 그대로 수락한다")
    void ingest_schemaVersionMismatch_stillAccepted() {
        when(eventWhitelist.isKnownEvent("chat_message_sent")).thenReturn(true);
        EventEnvelope event = new EventEnvelope("e1", "chat_message_sent", 2, VALID_TS,
                "anon-1", "user-1", "session-1", "1.0.0", "ios", "17.0", Map.of());

        EventsIngestResponse response = eventIngestService.ingest(List.of(event), "req-1");

        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.rejected()).isEmpty();
        verify(eventLogWriter).write(event, "req-1");
    }

    @Test
    @DisplayName("#322 — schema_version이 null이어도(필수 5종 아님) 거부하지 않고 수락한다")
    void ingest_nullSchemaVersion_stillAccepted() {
        when(eventWhitelist.isKnownEvent("chat_message_sent")).thenReturn(true);
        EventEnvelope event = new EventEnvelope("e1", "chat_message_sent", null, VALID_TS,
                "anon-1", "user-1", "session-1", "1.0.0", "ios", "17.0", Map.of());

        EventsIngestResponse response = eventIngestService.ingest(List.of(event), "req-1");

        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.rejected()).isEmpty();
    }

    @Test
    @DisplayName("ts_client가 ISO8601 형식이 아니면 TS_CLIENT_INVALID_FORMAT으로 거부된다")
    void ingest_malformedTsClient_rejected() {
        EventEnvelope event = new EventEnvelope("e1", "chat_message_sent", 3, "not-a-date",
                "anon-1", "user-1", "session-1", "1.0.0", "ios", "17.0", Map.of());

        EventsIngestResponse response = eventIngestService.ingest(List.of(event), "req-1");

        assertThat(response.rejected().get(0).reason()).isEqualTo("TS_CLIENT_INVALID_FORMAT");
    }

    @Test
    @DisplayName("부분 성공 — 배치 안에서 유효한 것과 거부된 것이 섞여도 전체가 막히지 않는다")
    void ingest_mixedBatch_partialSuccess() {
        when(eventWhitelist.isKnownEvent("chat_message_sent")).thenReturn(true);
        EventEnvelope valid = validEvent("e1");
        EventEnvelope invalid = new EventEnvelope("e2", null, 3, VALID_TS,
                "anon-1", "user-1", "session-1", "1.0.0", "ios", "17.0", Map.of());

        EventsIngestResponse response = eventIngestService.ingest(List.of(valid, invalid), "req-1");

        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.rejected()).hasSize(1);
        assertThat(response.rejected().get(0).eventId()).isEqualTo("e2");
    }
}
