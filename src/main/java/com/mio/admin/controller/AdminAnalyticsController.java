package com.mio.admin.controller;

import com.mio.admin.dto.ReactionRetentionResponse;
import com.mio.admin.service.AdminReactionRetentionService;
import com.mio.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자용 분석 조회 (이슈 #476). {@code ROLE_ADMIN} 필요.
 */
@RestController
@RequestMapping("/v1/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final AdminReactionRetentionService adminReactionRetentionService;

    @GetMapping("/reaction-retention")
    public ResponseEntity<ApiResponse<ReactionRetentionResponse>> getReactionRetention() {
        return ResponseEntity.ok(ApiResponse.ok(adminReactionRetentionService.getReactionRetention()));
    }
}
