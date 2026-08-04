package com.mio.events.controller;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.events.dto.EventEnvelope;
import com.mio.events.dto.EventsIngestResponse;
import com.mio.events.service.EventIngestService;
import com.mio.events.service.EventRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
public class EventsController {

    private static final int MAX_BATCH_SIZE = 100;
    private static final long MAX_REQUEST_BYTES = 1024L * 1024L;

    private final EventIngestService eventIngestService;
    private final EventRateLimiter eventRateLimiter;

    @PostMapping
    public ResponseEntity<ApiResponse<EventsIngestResponse>> ingest(
            HttpServletRequest request,
            @RequestHeader("X-Device-Id") String deviceId,
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
        EventsIngestResponse response = eventIngestService.ingest(events, requestId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }
}
