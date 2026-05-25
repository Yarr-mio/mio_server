package com.mio.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DeviceTokenRegisterRequest(
        @NotBlank String deviceId,
        @NotBlank @Pattern(regexp = "ios|android", message = "platform must be 'ios' or 'android'") String platform,
        @NotBlank String token
) {}
