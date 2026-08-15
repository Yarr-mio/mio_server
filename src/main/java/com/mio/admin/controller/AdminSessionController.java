package com.mio.admin.controller;

import com.mio.admin.dto.SessionCostResponse;
import com.mio.admin.dto.SessionTimelineResponse;
import com.mio.admin.service.AdminSessionCostService;
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
 * <p>{@code ROLE_ADMIN} 필요 (이슈 #279). 내부 개발팀 전용 전제로 대화 원문도 마스킹 없이
 * 그대로 노출한다 — 인증은 추가됐지만 세분화된 열람 권한 제한은 이번 범위 밖.
 */
@RestController
@RequestMapping("/v1/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final AdminSessionService adminSessionService;
    private final AdminSessionCostService adminSessionCostService;

    @GetMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<SessionTimelineResponse>> getTimeline(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(ApiResponse.ok(adminSessionService.getTimeline(sessionId)));
    }

    @GetMapping("/{sessionId}/cost")
    public ResponseEntity<ApiResponse<SessionCostResponse>> getCost(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(ApiResponse.ok(adminSessionCostService.getCost(sessionId)));
    }
}
