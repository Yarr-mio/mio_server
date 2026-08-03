package com.mio.admin.controller;

import com.mio.admin.dto.SessionTimelineResponse;
import com.mio.admin.service.AdminSessionService;
import com.mio.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 운영자용 세션 통합 조회 — 한 세션의 대화·안전판정·위기·감사기록을 시간순으로 반환한다.
 *
 * <p><b>인증 미포함 — Sprint01 범위 밖 결정.</b> 내부 개발팀 전용 전제로 대화 원문도 마스킹 없이
 * 그대로 노출한다. Admin 로그인 시스템이 생기면 이 위에 인증 필터만 얹는 구조로 지금부터
 * 설계해둔다 (컨트롤러 로직과 인증을 분리).
 */
@RestController
@RequestMapping("/v1/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final AdminSessionService adminSessionService;

    @GetMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<SessionTimelineResponse>> getTimeline(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(ApiResponse.ok(adminSessionService.getTimeline(sessionId)));
    }
}
