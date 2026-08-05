package com.mio.events.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.events.config.EventWhitelist;
import com.mio.events.dto.EventEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 이슈 #326 — journey-events-rejected 로거와 property drop/invalid 로그 라인 검증. */
@ExtendWith(MockitoExtension.class)
class EventLogWriterTest {

    @Mock private EventWhitelist eventWhitelist;

    private EventLogWriter eventLogWriter;
    private ListAppender<ILoggingEvent> journeyAppender;
    private ListAppender<ILoggingEvent> rejectedAppender;
    private ListAppender<ILoggingEvent> classAppender;

    private static final String VALID_TS = "2026-08-06T21:13:02+09:00";

    @BeforeEach
    void setUp() {
        eventLogWriter = new EventLogWriter(eventWhitelist, new ObjectMapper());
        journeyAppender = attach("journey-events");
        rejectedAppender = attach("journey-events-rejected");
        classAppender = attach(EventLogWriter.class.getName());
    }

    @AfterEach
    void tearDown() {
        detach("journey-events", journeyAppender);
        detach("journey-events-rejected", rejectedAppender);
        detach(EventLogWriter.class.getName(), classAppender);
    }

    private ListAppender<ILoggingEvent> attach(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(String loggerName, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggerName)).detachAppender(appender);
    }

    private EventEnvelope event(Map<String, Object> properties) {
        return new EventEnvelope("e1", "chat_message_sent", 3, VALID_TS,
                "anon-1", "user-1", "session-1", "1.0.0", "ios", "17.0", properties);
    }

    @Test
    @DisplayName("카탈로그에 없는 키는 journey_property_dropped로 로그되고 결과에서 빠진다")
    void write_unknownKey_logsDropped() {
        when(eventWhitelist.isKnownProperty("chat_message_sent", "made_up_key")).thenReturn(false);

        eventLogWriter.write(event(Map.of("made_up_key", "x")), "req-1");

        assertThat(classAppender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("journey_property_dropped")
                        && e.getFormattedMessage().contains("made_up_key"));
        assertThat(journeyAppender.list).hasSize(1);
        assertThat(journeyAppender.list.get(0).getFormattedMessage()).doesNotContain("made_up_key");
    }

    @Test
    @DisplayName("아는 키인데 값이 도메인 밖이면 journey_property_invalid로 로그되고 결과에서 빠진다")
    void write_invalidValue_logsInvalid() {
        when(eventWhitelist.isKnownProperty("chat_message_sent", "message_index")).thenReturn(true);
        when(eventWhitelist.isValidPropertyValue("chat_message_sent", "message_index", "not-a-number"))
                .thenReturn(false);

        eventLogWriter.write(event(Map.of("message_index", "not-a-number")), "req-1");

        assertThat(classAppender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("journey_property_invalid")
                        && e.getFormattedMessage().contains("message_index"));
        assertThat(journeyAppender.list.get(0).getFormattedMessage()).doesNotContain("not-a-number");
    }

    @Test
    @DisplayName("유효한 속성은 journey-events 로거에 그대로 남는다")
    void write_validProperty_isKept() {
        when(eventWhitelist.isKnownProperty("chat_message_sent", "message_index")).thenReturn(true);
        when(eventWhitelist.isValidPropertyValue("chat_message_sent", "message_index", 3)).thenReturn(true);

        eventLogWriter.write(event(Map.of("message_index", 3)), "req-1");

        assertThat(journeyAppender.list).hasSize(1);
        assertThat(journeyAppender.list.get(0).getFormattedMessage()).contains("\"message_index\":3");
    }

    @Test
    @DisplayName("#326 — 거부된 이벤트는 원본 그대로 journey-events-rejected 로거에 남는다")
    void writeRejected_logsOriginalPayload() {
        EventEnvelope event = event(Map.of("message_index", 3));

        eventLogWriter.writeRejected(event, 2, "UNKNOWN_EVENT_NAME", "req-1");

        assertThat(rejectedAppender.list).hasSize(1);
        String message = rejectedAppender.list.get(0).getFormattedMessage();
        assertThat(message).contains("\"index\":2");
        assertThat(message).contains("\"reject_reason\":\"UNKNOWN_EVENT_NAME\"");
        assertThat(message).contains("\"event_id\":\"e1\"");
        assertThat(journeyAppender.list).isEmpty();
    }
}
