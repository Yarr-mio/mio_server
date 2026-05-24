package com.mio.session.controller;

import com.mio.common.response.ApiResponse;
import com.mio.session.dto.*;
import com.mio.session.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<ActiveSessionResponse>> getActiveSession(
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(sessionService.getActiveSession(userId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SessionResponse>> createSession(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody CreateSessionRequest request) {
        SessionResponse response = sessionService.createSession(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping(value = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @RequestBody SendMessageRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        Thread.ofVirtual().start(() -> sessionService.streamMessage(userId, sessionId, request, emitter));
        return emitter;
    }

    @PostMapping("/{sessionId}/end")
    public ResponseEntity<ApiResponse<EndSessionResponse>> endSession(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId) {
        EndSessionResponse response = sessionService.endSession(userId, sessionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
