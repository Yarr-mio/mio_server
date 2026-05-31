package com.mio.mypage.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.mypage.dto.UserProfileResponse;
import com.mio.mypage.dto.UserProfileUpdateRequest;
import com.mio.mypage.dto.UserProfileUpdateResponse;
import com.mio.mypage.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/v1/users/me")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                userProfileService.getProfile(PrincipalUtils.resolveUserId(principal))
        ));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<UserProfileUpdateResponse>> updateProfile(
            Principal principal,
            @RequestBody UserProfileUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                userProfileService.updateProfile(PrincipalUtils.resolveUserId(principal), request)
        ));
    }
}
