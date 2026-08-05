package com.mio.events.controller;

import com.mio.auth.service.JwtTokenService;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.events.dto.EventEnvelope;
import com.mio.events.dto.EventsIngestResponse;
import com.mio.events.service.EventIngestService;
import com.mio.events.service.EventRateLimiter;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 성장분석 이벤트 수집 API (이슈 #285). 강민석 대시보드 파이프라인의 입구 —
 * #277 세션 감사/조회와는 완전히 별개 시스템이다 (Notion "15_Events_이벤트수집" 참고).
 *
 * <p>인증 불필요 — 로그인 전 익명 이벤트도 받아야 한다.
 */
@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
@Slf4j
public class EventsController {

    private static final int MAX_BATCH_SIZE = 100;
    private static final long MAX_REQUEST_BYTES = 1024L * 1024L;
    private static final String BEARER_PREFIX = "Bearer ";

    private final EventIngestService eventIngestService;
    private final EventRateLimiter eventRateLimiter;
    private final JwtTokenService jwtTokenService;

    @PostMapping
    public ResponseEntity<ApiResponse<EventsIngestResponse>> ingest(
            HttpServletRequest request,
            @RequestHeader("X-Device-Id") String deviceId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody List<EventEnvelope> events) {

        if (events.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_REQUEST_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        eventRateLimiter.check(deviceId);

        String requestId = String.valueOf(request.getAttribute("traceId"));
        List<EventEnvelope> reconciled = reconcileUserId(events, authorization);
        EventsIngestResponse response = eventIngestService.ingest(reconciled, requestId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    /**
     * 이슈 #324 — 무인증 엔드포인트라 envelope의 user_id는 자기 신고다. Authorization
     * 헤더가 있으면 토큰 sub로 덮어써서 임의 user_id로 타인의 지표를 오염시키는 걸 막는다.
     * 헤더가 없거나 파싱에 실패해도 요청 자체는 막지 않는다 — 익명 허용은 그대로 유지한다.
     */
    private List<EventEnvelope> reconcileUserId(List<EventEnvelope> events, String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return events;
        }
        String tokenUserId;
        try {
            tokenUserId = jwtTokenService.parseToken(authorization.substring(BEARER_PREFIX.length())).getSubject();
        } catch (JwtException e) {
            log.debug("이벤트 수집 요청의 Authorization 토큰이 유효하지 않아 무시함: {}", e.getMessage());
            return events;
        }
        return events.stream()
                .map(event -> tokenUserId.equals(event.userId()) ? event : event.withUserId(tokenUserId))
                .toList();
    }
}
