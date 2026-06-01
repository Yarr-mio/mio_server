package com.mio.auth.controller;

import com.mio.auth.dto.DevTokenRequest;
import com.mio.auth.dto.DevTokenResponse;
import com.mio.auth.service.JwtTokenService;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.user.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth/dev")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "auth.dev-token-enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class DevAuthController {

    private static final String DEV_DEVICE_ID = "dev-device";
    private static final int EXPIRES_IN = 900;

    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<DevTokenResponse>> issueDevToken(
            @Valid @RequestBody DevTokenRequest request) {
        var user = userRepository.findById(request.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String token = jwtTokenService.generateAccessToken(
                user.getId().toString(),
                DEV_DEVICE_ID,
                user.isMinor()
        );

        return ResponseEntity.ok(ApiResponse.ok(new DevTokenResponse(token, EXPIRES_IN)));
    }
}
