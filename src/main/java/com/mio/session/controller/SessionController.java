package com.mio.session.controller;

import com.mio.common.response.ApiResponse;
import com.mio.session.dto.*;
import com.mio.session.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;
import java.util.UUID;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;

@RestController
@RequestMapping("/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<ActiveSessionResponse>> getActiveSession(
            Principal principal) {
        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(sessionService.getActiveSession(userId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SessionResponse>> createSession(
            Principal principal,
            @Valid @RequestBody CreateSessionRequest request) {
        UUID userId = resolveUserId(principal);
        SessionResponse response = sessionService.createSession(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping(value = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            HttpServletResponse response,
            Principal principal,
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SendMessageRequest request) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        UUID userId = resolveUserId(principal);
        sessionService.validateMessageRequest(userId, sessionId, idempotencyKey);
        SseEmitter emitter = new SseEmitter(60_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> emitter.complete());
        Thread.ofVirtual().start(() -> sessionService.streamMessage(userId, sessionId, request, emitter, idempotencyKey));
        return emitter;
    }

    @PostMapping("/{sessionId}/end")
    public ResponseEntity<ApiResponse<EndSessionResponse>> endSession(
            Principal principal,
            @PathVariable UUID sessionId) {
        UUID userId = resolveUserId(principal);
        EndSessionResponse response = sessionService.endSession(userId, sessionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{sessionId}/summary")
    public ResponseEntity<ApiResponse<SessionSummaryResponse>> getSessionSummary(
            Principal principal,
            @PathVariable UUID sessionId) {
        UUID userId = resolveUserId(principal);
        SessionSummaryResponse response = sessionService.getSessionSummary(userId, sessionId);
        HttpStatus status = "pending".equals(response.summaryStatus()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(response));
    }

    private UUID resolveUserId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효한 사용자 식별자가 필요합니다.");
        }
    }
}
