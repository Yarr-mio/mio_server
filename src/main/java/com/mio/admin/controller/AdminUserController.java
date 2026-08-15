package com.mio.admin.controller;

import com.mio.admin.dto.UserMonthlyCostResponse;
import com.mio.admin.service.AdminUserCostService;
import com.mio.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 운영자용 유저 단위 조회 (이슈 #434).
 *
 * <p>{@code ROLE_ADMIN} 필요 — {@code /v1/admin/**} 전체가 {@code SecurityConfig}에서
 * 일괄 게이트된다({@code AdminSessionController}와 동일).
 */
@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserCostService adminUserCostService;

    /** @param month "yyyy-MM" 형식. 생략하면 이번 달(Asia/Seoul 기준) */
    @GetMapping("/{userId}/cost-monthly")
    public ResponseEntity<ApiResponse<UserMonthlyCostResponse>> getMonthlyCost(
            @PathVariable UUID userId,
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(ApiResponse.ok(adminUserCostService.getMonthlyCost(userId, month)));
    }
}
