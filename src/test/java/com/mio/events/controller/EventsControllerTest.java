package com.mio.events.controller;

import com.mio.auth.service.JwtTokenService;
import com.mio.events.dto.EventEnvelope;
import com.mio.events.dto.EventsIngestResponse;
import com.mio.events.service.EventIngestService;
import com.mio.events.service.EventRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 이슈 #324 — Authorization 토큰 sub와 envelope user_id 대조(reconcileUserId) 검증. */
@ExtendWith(MockitoExtension.class)
class EventsControllerTest {

    @Mock private EventIngestService eventIngestService;
    @Mock private EventRateLimiter eventRateLimiter;
    @Mock private HttpServletRequest request;

    private EventsController controller;
    private JwtTokenService jwtTokenService;

    private static final String VALID_TS = "2026-08-06T21:13:02+09:00";

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService("test-secret-key-32-bytes-minimum!!", 900);
        controller = new EventsController(eventIngestService, eventRateLimiter, jwtTokenService);
        when(eventIngestService.ingest(any(), any())).thenReturn(new EventsIngestResponse(1, List.of()));
    }

    private EventEnvelope eventWithUserId(String userId) {
        return new EventEnvelope("e1", "chat_message_sent", 3, VALID_TS,
                "anon-1", userId, "session-1", "1.0.0", "ios", "17.0", Map.of());
    }

    @Test
    @DisplayName("Authorization 토큰이 있고 user_id가 다르면 서버가 토큰 sub로 덮어쓴다")
    void ingest_tokenMismatch_overridesUserId() {
        String token = jwtTokenService.generateAccessToken("token-user-id", "device-1", false, false);
        EventEnvelope event = eventWithUserId("client-claimed-user-id");

        controller.ingest(request, "device-1", "Bearer " + token, List.of(event));

        ArgumentCaptor<List<EventEnvelope>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventIngestService).ingest(captor.capture(), any());
        assertThat(captor.getValue().get(0).userId()).isEqualTo("token-user-id");
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 user_id를 그대로 둔다 (익명 허용 유지)")
    void ingest_noAuthorizationHeader_passesThrough() {
        EventEnvelope event = eventWithUserId("client-claimed-user-id");

        controller.ingest(request, "device-1", null, List.of(event));

        ArgumentCaptor<List<EventEnvelope>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventIngestService).ingest(captor.capture(), any());
        assertThat(captor.getValue().get(0).userId()).isEqualTo("client-claimed-user-id");
    }

    @Test
    @DisplayName("Authorization 토큰이 유효하지 않으면 요청을 막지 않고 그대로 수용한다")
    void ingest_invalidToken_doesNotBlockRequest() {
        EventEnvelope event = eventWithUserId("client-claimed-user-id");

        controller.ingest(request, "device-1", "Bearer not-a-real-token", List.of(event));

        ArgumentCaptor<List<EventEnvelope>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventIngestService).ingest(captor.capture(), any());
        assertThat(captor.getValue().get(0).userId()).isEqualTo("client-claimed-user-id");
    }
}
