package com.mio.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.notification.domain.DeviceToken;

public record DeviceTokenResponse(
        boolean success,
        @JsonProperty("device_id") String deviceId,
        String platform
) {

    public static DeviceTokenResponse from(DeviceToken token) {
        return new DeviceTokenResponse(true, token.getDeviceId(), token.getPlatform());
    }
}
