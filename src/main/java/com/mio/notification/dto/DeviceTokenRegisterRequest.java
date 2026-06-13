package com.mio.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DeviceTokenRegisterRequest(
        @JsonProperty("device_id") @NotBlank String deviceId,
        @JsonProperty("push_token") @NotBlank String pushToken,
        @NotBlank @Pattern(regexp = "ios|android", message = "platform must be 'ios' or 'android'") String platform,
        @JsonProperty("app_version") @NotBlank String appVersion
) {}
