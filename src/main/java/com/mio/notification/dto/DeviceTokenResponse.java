package com.mio.notification.dto;

import com.mio.notification.domain.DeviceToken;

import java.util.UUID;

public record DeviceTokenResponse(UUID id, String deviceId, String platform) {

    public static DeviceTokenResponse from(DeviceToken token) {
        return new DeviceTokenResponse(token.getId(), token.getDeviceId(), token.getPlatform());
    }
}
